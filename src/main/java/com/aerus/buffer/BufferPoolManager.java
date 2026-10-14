package com.aerus.buffer;

import com.aerus.common.Constants;
import com.aerus.storage.DiskManager;
import com.aerus.storage.Page;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.locks.ReentrantLock;

public class BufferPoolManager implements BufferPool {

    private final DiskManager diskManager;
    private final Replacer replacer;
    private final Page[] pool;
    
    private final Map<Integer, Integer> pageTable;
    
    private final LinkedList<Integer> freeList;
    
    private final ReentrantLock lock = new ReentrantLock();

    private int bufferHits = 0;
    private int bufferMisses = 0;

    public BufferPoolManager(int poolSize, DiskManager diskManager, Replacer replacer) {
        this.diskManager = diskManager;
        this.replacer = replacer;
        this.pool = new Page[poolSize];
        this.pageTable = new HashMap<>();
        this.freeList = new LinkedList<>();

        for (int i = 0; i < poolSize; i++) {
            freeList.add(i);
            pool[i] = new Page();
        }
    }

    public int getBufferHits() { return bufferHits; }
    public int getBufferMisses() { return bufferMisses; }

    @Override
    public Page newPage() throws IOException {
        lock.lock();
        try {
            int frameId = getAvailableFrame();
            if (frameId == -1) {
                return null;
            }

            int newPageId = diskManager.allocatePage();
            
            Page page = pool[frameId];
            page.resetMeta();
            page.setPageId(newPageId);
            page.pin();
            
            pageTable.put(newPageId, frameId);
            replacer.pin(frameId);
            
            return page;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Page fetchPage(int pageId) throws IOException {
        lock.lock();
        try {
            if (pageTable.containsKey(pageId)) {
                bufferHits++;
                int frameId = pageTable.get(pageId);
                Page page = pool[frameId];
                page.pin();
                replacer.pin(frameId);
                return page;
            }

            bufferMisses++;
            int frameId = getAvailableFrame();
            if (frameId == -1) {
                return null;
            }

            Page page = pool[frameId];
            page.resetMeta();
            page.setPageId(pageId);
            page.pin();
            
            diskManager.readPage(pageId, page.getData());
            
            pageTable.put(pageId, frameId);
            replacer.pin(frameId);
            
            return page;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean unpinPage(int pageId, boolean dirty) {
        lock.lock();
        try {
            if (!pageTable.containsKey(pageId)) {
                return false;
            }
            int frameId = pageTable.get(pageId);
            Page page = pool[frameId];
            
            if (page.getPinCount() <= 0) {
                return false;
            }
            
            page.unpin();
            if (dirty) {
                page.setDirty(true);
            }
            
            if (page.getPinCount() == 0) {
                replacer.unpin(frameId);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean flushPage(int pageId) throws IOException {
        lock.lock();
        try {
            if (!pageTable.containsKey(pageId)) {
                return false;
            }
            int frameId = pageTable.get(pageId);
            Page page = pool[frameId];
            
            if (page.isDirty()) {
                diskManager.writePage(page.getPageId(), page.getData());
                page.setDirty(false);
                page.setRecLSN(Constants.INVALID_LSN);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void flushAll() throws IOException {
        lock.lock();
        try {
            for (Page page : pool) {
                if (page.getPageId() != Constants.INVALID_PAGE_ID && page.isDirty()) {
                    diskManager.writePage(page.getPageId(), page.getData());
                    page.setDirty(false);
                    page.setRecLSN(Constants.INVALID_LSN);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<DirtyEntry> dirtyPageTable() {
        lock.lock();
        try {
            List<DirtyEntry> dpt = new ArrayList<>();
            for (Page page : pool) {
                if (page.isDirty()) {
                    dpt.add(new DirtyEntry(page.getPageId(), page.getRecLSN()));
                }
            }
            return dpt;
        } finally {
            lock.unlock();
        }
    }

    private int getAvailableFrame() throws IOException {
        if (!freeList.isEmpty()) {
            return freeList.removeFirst();
        }
        
        OptionalInt victim = replacer.victim();
        if (victim.isPresent()) {
            int frameId = victim.getAsInt();
            Page page = pool[frameId];
            
            if (page.isDirty()) {
                diskManager.writePage(page.getPageId(), page.getData());
            }
            
            pageTable.remove(page.getPageId());
            return frameId;
        }
        
        return -1;
    }
}