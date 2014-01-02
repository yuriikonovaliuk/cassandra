package org.apache.cassandra.utils.memory;

import org.apache.cassandra.utils.concurrent.OpOrdering;
import org.apache.cassandra.utils.concurrent.WaitQueue;

import java.util.concurrent.atomic.AtomicLongFieldUpdater;

public final class MemoryOwner
{

    // the tracker we are owning memory from
    private final MemoryTracker tracker;

    // the amount of memory/resource owned by this object
    private volatile long owns;
    // the amount of memory we are reporting to collect; this may be inaccurate, but is close
    // and is used only to ensure that once we have reclaimed we mark the tracker with the same amount
    private volatile long reclaiming;

    private static final AtomicLongFieldUpdater<MemoryOwner> ownsUpdater = AtomicLongFieldUpdater.newUpdater(MemoryOwner.class, "owns");

    MemoryOwner(MemoryTracker tracker)
    {
        this.tracker = tracker;
    }

    // must be called with exclusive access, but safe to call multiple times
    void markAllReclaiming()
    {
        long prev = reclaiming;
        long cur = owns;
        reclaiming = cur;
        tracker.adjustReclaiming(cur - prev);
    }

    // should only be called once we know we will never allocate to the object again.
    // currently no corroboration/enforcement of this is performed.
    void releaseAll()
    {
        tracker.adjustAcquired(-ownsUpdater.getAndSet(this, 0), false);
        tracker.adjustReclaiming(-reclaiming);
    }

    // if this.owns > size, subtract size from this.owns (atomically) and add it to the provided MemoryOwner
    boolean transfer(int size, MemoryOwner to)
    {
        while (true)
        {
            long cur = owns;
            long next = cur - size;
            if (next < 0)
                return false;
            if (ownsUpdater.compareAndSet(this, cur, next))
            {
                ownsUpdater.addAndGet(to, size);
                return true;
            }
        }
    }

    // allocate memory in the tracker, and mark ourselves as owning it
    public void allocate(int size, OpOrdering.Ordered writeOp)
    {
        while (true)
        {
            if (tracker.tryAllocate(size))
            {
                acquired(size);
                return;
            }
            WaitQueue.Signal signal = writeOp.safeIsBlockingSignal(tracker.hasRoom.register());
            boolean allocated = tracker.tryAllocate(size);
            if (allocated || writeOp.isBlocking())
            {
                signal.cancel();
                if (allocated) // if we allocated, take ownership
                    acquired(size);
                else // otherwise we're blocking so we're permitted to overshoot our constraints, to just allocate without blocking
                    allocated(size);
                return;
            }
            else
                signal.awaitUninterruptibly();
        }
    }

    // retroactively mark an amount allocated amd acquired in the tracker, and owned by us
    void allocated(int size)
    {
        tracker.adjustAcquired(size, true);
        ownsUpdater.addAndGet(this, size);
    }

    // retroactively mark an amount acquired in the tracker, and owned by us
    void acquired(int size)
    {
        tracker.adjustAcquired(size, false);
        ownsUpdater.addAndGet(this, size);
    }

    void release(int size)
    {
        tracker.adjustAcquired(-size, false);
        ownsUpdater.addAndGet(this, -size);
    }

    public long owns()
    {
        return owns;
    }

    public long reclaiming()
    {
        return reclaiming;
    }

    public float ownershipRatio()
    {
        return owns / (float) tracker.limit;
    }

}

