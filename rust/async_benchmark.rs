use std::alloc::{GlobalAlloc, Layout, System};
use std::env;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant};
use tokio::sync::{Notify, watch};

const DEFAULT_TASK_COUNT: usize = 1_000_000;
const DEFAULT_IO_WAIT_MILLIS: u64 = 100;

struct TrackingAllocator;

static CURRENT_HEAP: AtomicUsize = AtomicUsize::new(0);
static PEAK_HEAP: AtomicUsize = AtomicUsize::new(0);

#[global_allocator]
static ALLOCATOR: TrackingAllocator = TrackingAllocator;

unsafe impl GlobalAlloc for TrackingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let pointer = unsafe { System.alloc(layout) };
        if !pointer.is_null() {
            add_allocated(layout.size());
        }
        pointer
    }

    unsafe fn dealloc(&self, pointer: *mut u8, layout: Layout) {
        unsafe { System.dealloc(pointer, layout) };
        CURRENT_HEAP.fetch_sub(layout.size(), Ordering::Relaxed);
    }

    unsafe fn realloc(&self, pointer: *mut u8, old: Layout, new_size: usize) -> *mut u8 {
        let new_pointer = unsafe { System.realloc(pointer, old, new_size) };
        if !new_pointer.is_null() {
            if new_size >= old.size() {
                add_allocated(new_size - old.size());
            } else {
                CURRENT_HEAP.fetch_sub(old.size() - new_size, Ordering::Relaxed);
            }
        }
        new_pointer
    }
}

fn add_allocated(bytes: usize) {
    let current = CURRENT_HEAP.fetch_add(bytes, Ordering::Relaxed) + bytes;
    PEAK_HEAP.fetch_max(current, Ordering::Relaxed);
}

#[tokio::main(flavor = "multi_thread")]
async fn main() {
    let task_count = argument(1, DEFAULT_TASK_COUNT);
    let io_wait_millis = argument(2, DEFAULT_IO_WAIT_MILLIS);
    assert!(task_count > 0, "task count must be positive");

    let heap_before = CURRENT_HEAP.load(Ordering::Relaxed);
    PEAK_HEAP.store(heap_before, Ordering::Relaxed);
    let cpu_before = process_cpu_seconds();
    let wall_before = Instant::now();

    let (gate_sender, gate_receiver) = watch::channel(false);
    let completed = Arc::new(AtomicUsize::new(0));
    let all_done = Arc::new(Notify::new());
    let wait_duration = Duration::from_millis(io_wait_millis as u64);

    println!("Simulated I/O wait:    {io_wait_millis} ms per task (Tokio async sleep)");

    for _ in 0..task_count {
        let mut gate = gate_receiver.clone();
        let completed = Arc::clone(&completed);
        let all_done = Arc::clone(&all_done);
        tokio::spawn(async move {
            if !*gate.borrow() {
                gate.changed().await.expect("gate sender was dropped");
            }
            tokio::time::sleep(wait_duration).await;
            if completed.fetch_add(1, Ordering::Relaxed) + 1 == task_count {
                all_done.notify_one();
            }
        });
    }

    gate_sender
        .send(true)
        .expect("all gate receivers disappeared");
    while completed.load(Ordering::Relaxed) != task_count {
        all_done.notified().await;
    }

    let wall_time = wall_before.elapsed();
    let cpu_time = process_cpu_seconds() - cpu_before;
    let heap_after = CURRENT_HEAP.load(Ordering::Relaxed);
    let peak_heap = PEAK_HEAP.load(Ordering::Relaxed);
    let processors = std::thread::available_parallelism().map_or(1, |value| value.get());
    let one_core_cpu = 100.0 * cpu_time / wall_time.as_secs_f64();
    let machine_cpu = one_core_cpu / processors as f64;
    let throughput = task_count as f64 / wall_time.as_secs_f64();

    assert_eq!(completed.load(Ordering::Relaxed), task_count);
    println!("\n=== Rust Tokio tasks ===");
    println!("Tasks completed:        {}", comma(task_count));
    println!("Wall-clock time:       {:.3} s", wall_time.as_secs_f64());
    println!(
        "Throughput:            {} tasks/s",
        comma(throughput as usize)
    );
    println!("Process CPU time:      {cpu_time:.3} s");
    println!("Average CPU usage:     {one_core_cpu:.1}% of one core");
    println!("Average machine CPU:   {machine_cpu:.1}% of {processors} cores");
    println!("Heap before workload:  {:.1} MiB", mib(heap_before));
    println!("Peak heap used:        {:.1} MiB", mib(peak_heap));
    println!("Heap after workload:   {:.1} MiB", mib(heap_after));
}

fn argument<T>(index: usize, fallback: T) -> T
where
    T: std::str::FromStr,
    T::Err: std::fmt::Debug,
{
    env::args()
        .nth(index)
        .map(|value| value.parse().expect("invalid numeric argument"))
        .unwrap_or(fallback)
}

fn process_cpu_seconds() -> f64 {
    let mut usage = std::mem::MaybeUninit::<libc::rusage>::uninit();
    let result = unsafe { libc::getrusage(libc::RUSAGE_SELF, usage.as_mut_ptr()) };
    assert_eq!(result, 0, "getrusage failed");
    let usage = unsafe { usage.assume_init() };
    timeval_seconds(usage.ru_utime) + timeval_seconds(usage.ru_stime)
}

fn timeval_seconds(value: libc::timeval) -> f64 {
    value.tv_sec as f64 + value.tv_usec as f64 / 1_000_000.0
}

fn mib(bytes: usize) -> f64 {
    bytes as f64 / 1024.0 / 1024.0
}

fn comma(value: usize) -> String {
    let digits = value.to_string();
    let mut output = String::with_capacity(digits.len() + digits.len() / 3);
    for (index, character) in digits.chars().enumerate() {
        if index > 0 && (digits.len() - index).is_multiple_of(3) {
            output.push(',');
        }
        output.push(character);
    }
    output
}
