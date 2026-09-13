package main

import (
	"fmt"
	"os"
	"runtime"
	"strconv"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

const (
	defaultTaskCount    = 1_000_000
	defaultIOWaitMillis = 100
)

func main() {
	taskCount := argument(1, defaultTaskCount)
	ioWaitMillis := argument(2, defaultIOWaitMillis)
	if taskCount <= 0 {
		panic("task count must be positive")
	}
	if ioWaitMillis < 0 {
		panic("I/O wait must not be negative")
	}

	runtime.GC()
	heapBefore := heapAlloc()
	peakHeap := heapBefore
	stopSampler := make(chan struct{})
	samplerDone := make(chan struct{})
	go samplePeakHeap(&peakHeap, stopSampler, samplerDone)

	var usageBefore syscall.Rusage
	if err := syscall.Getrusage(syscall.RUSAGE_SELF, &usageBefore); err != nil {
		panic(err)
	}

	gate := make(chan struct{})
	var completed atomic.Int64
	var workers sync.WaitGroup
	workers.Add(taskCount)
	waitDuration := time.Duration(ioWaitMillis) * time.Millisecond

	fmt.Printf("Simulated I/O wait:    %d ms per task (goroutine sleep)\n", ioWaitMillis)
	wallBefore := time.Now()
	for i := 0; i < taskCount; i++ {
		go func() {
			defer workers.Done()
			<-gate
			time.Sleep(waitDuration)
			completed.Add(1)
		}()
	}
	close(gate)
	workers.Wait()
	wallTime := time.Since(wallBefore)

	var usageAfter syscall.Rusage
	if err := syscall.Getrusage(syscall.RUSAGE_SELF, &usageAfter); err != nil {
		panic(err)
	}
	close(stopSampler)
	<-samplerDone
	heapAfter := heapAlloc()
	updatePeak(&peakHeap, heapAfter)

	if completed.Load() != int64(taskCount) {
		panic(fmt.Sprintf("expected %d completions, got %d", taskCount, completed.Load()))
	}

	cpuTime := rusageDuration(usageAfter) - rusageDuration(usageBefore)
	oneCoreCPU := 100 * cpuTime.Seconds() / wallTime.Seconds()
	machineCPU := oneCoreCPU / float64(runtime.NumCPU())
	throughput := float64(taskCount) / wallTime.Seconds()

	fmt.Println("\n=== Go goroutines ===")
	fmt.Printf("Tasks completed:        %s\n", comma(taskCount))
	fmt.Printf("Wall-clock time:       %.3f s\n", wallTime.Seconds())
	fmt.Printf("Throughput:            %s tasks/s\n", comma(int(throughput)))
	fmt.Printf("Process CPU time:      %.3f s\n", cpuTime.Seconds())
	fmt.Printf("Average CPU usage:     %.1f%% of one core\n", oneCoreCPU)
	fmt.Printf("Average machine CPU:   %.1f%% of %d cores\n", machineCPU, runtime.NumCPU())
	fmt.Printf("Heap before workload:  %.1f MiB\n", mib(heapBefore))
	fmt.Printf("Peak heap used:        %.1f MiB\n", mib(atomic.LoadUint64(&peakHeap)))
	fmt.Printf("Heap after workload:   %.1f MiB\n", mib(heapAfter))
}

func argument(index, fallback int) int {
	if len(os.Args) <= index {
		return fallback
	}
	value, err := strconv.Atoi(os.Args[index])
	if err != nil {
		panic(err)
	}
	return value
}

func samplePeakHeap(peak *uint64, stop <-chan struct{}, done chan<- struct{}) {
	defer close(done)
	ticker := time.NewTicker(10 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			updatePeak(peak, heapAlloc())
		case <-stop:
			updatePeak(peak, heapAlloc())
			return
		}
	}
}

func updatePeak(peak *uint64, candidate uint64) {
	for {
		current := atomic.LoadUint64(peak)
		if candidate <= current || atomic.CompareAndSwapUint64(peak, current, candidate) {
			return
		}
	}
}

func heapAlloc() uint64 {
	var memory runtime.MemStats
	runtime.ReadMemStats(&memory)
	return memory.HeapAlloc
}

func rusageDuration(usage syscall.Rusage) time.Duration {
	user := time.Duration(usage.Utime.Sec)*time.Second + time.Duration(usage.Utime.Usec)*time.Microsecond
	system := time.Duration(usage.Stime.Sec)*time.Second + time.Duration(usage.Stime.Usec)*time.Microsecond
	return user + system
}

func mib(bytes uint64) float64 {
	return float64(bytes) / 1024 / 1024
}

func comma(value int) string {
	digits := strconv.Itoa(value)
	for position := len(digits) - 3; position > 0; position -= 3 {
		digits = digits[:position] + "," + digits[position:]
	}
	return digits
}
