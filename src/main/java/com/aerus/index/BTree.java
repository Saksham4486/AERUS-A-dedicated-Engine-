package com.aerus.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.aerus.buffer.BufferPool;
import com.aerus.common.Bytes;
import com.aerus.common.Constants;
import com.aerus.storage.Page;

/**
 Disk-backed B+Tree index mapping 64-bit keys to 64-bit values.
  Node pages are stored inside BufferPool pages (4 KB).
  Page Header Format:
  [0..7]   : pageLSN (8 bytes)
  [8..9]   : isLeaf (short: 1 = leaf, 0 = internal)
  [10..11] : numKeys (short)
  [12..15] : nextLeafPageId (int, for leaf node sibling chaining)
  [16..19] : parentPageId (int) 
  Leaf Page Body:
  [20..] Array of (key: long, value: long) entries. Each entry = 16 bytes.
  Max entries per leaf page = (4096 - 20) / 16 = 254 entries.
 
  Internal Page Body:
  [20..23] : leftmostChildPageId (int)
  [24..]   : Array of (key: long, childPageId: int). Each entry = 12 bytes.
  Max entries per internal page = (4096 - 24) / 12 = 339 entries.
 */
public class BTree {

    public static final short PAGE_TYPE_INTERNAL = 0;
    public static final short PAGE_TYPE_LEAF = 1;

    public static final int HEADER_PAGE_LSN = 0;
    public static final int HEADER_IS_LEAF = 8;
    public static final int HEADER_NUM_KEYS = 10;
    public static final int HEADER_NEXT_LEAF = 12;
    public static final int HEADER_PARENT = 16;
    public static final int HEADER_SIZE = 20;

    public static final int LEAF_ENTRY_SIZE = 16;     // 8 bytes key + 8 bytes value
    public static final int MAX_LEAF_KEYS = (Constants.USABLE_PAGE_SIZE - HEADER_SIZE) / LEAF_ENTRY_SIZE; // 254: 20 + 254*16 = 4084 <= 4092

    public static final int INTERNAL_ENTRY_SIZE = 12; // 8 bytes key + 4 bytes child pageId
    public static final int MAX_INTERNAL_KEYS = (Constants.USABLE_PAGE_SIZE - HEADER_SIZE - 4) / INTERNAL_ENTRY_SIZE; // 339: 24 + 339*12 = 4092 <= 4092

    private final BufferPool bufferPool;
    private int rootPageId;

    public BTree(BufferPool bufferPool, int rootPageId) throws IOException {
        this.bufferPool = bufferPool;
        this.rootPageId = rootPageId;
        if (rootPageId != Constants.INVALID_PAGE_ID) {
            Page root = bufferPool.fetchPage(rootPageId);
            if (root != null) {
                bufferPool.unpinPage(rootPageId, false);
            }
        }
    }

    public int getRootPageId() {
        return rootPageId;
    }

    public synchronized void createIfEmpty() throws IOException {
        if (rootPageId == Constants.INVALID_PAGE_ID) {
            Page root = bufferPool.newPage();
            if (root == null) {
                throw new IOException("Failed to allocate root page for BTree");
            }
            rootPageId = root.getPageId();
            initLeafPage(root, Constants.INVALID_PAGE_ID);
            bufferPool.unpinPage(rootPageId, true);
        }
    }

    private void initLeafPage(Page page, int parent) {
        byte[] data = page.getData();
        Bytes.putShort(data, HEADER_IS_LEAF, PAGE_TYPE_LEAF);
        Bytes.putShort(data, HEADER_NUM_KEYS, (short) 0);
        Bytes.putInt(data, HEADER_NEXT_LEAF, Constants.INVALID_PAGE_ID);
        Bytes.putInt(data, HEADER_PARENT, parent);
    }

    private void initInternalPage(Page page, int parent, int leftChild) {
        byte[] data = page.getData();
        Bytes.putShort(data, HEADER_IS_LEAF, PAGE_TYPE_INTERNAL);
        Bytes.putShort(data, HEADER_NUM_KEYS, (short) 0);
        Bytes.putInt(data, HEADER_NEXT_LEAF, Constants.INVALID_PAGE_ID);
        Bytes.putInt(data, HEADER_PARENT, parent);
        Bytes.putInt(data, HEADER_SIZE, leftChild);
    }

    /**
     * Search for a key in the B+Tree.
     * Returns null if key not found.
     */
    public synchronized Long get(long key) throws IOException {
        if (rootPageId == Constants.INVALID_PAGE_ID) {
            return null;
        }
        int leafPageId = findLeafPage(key);
        Page leaf = bufferPool.fetchPage(leafPageId);
        try {
            int numKeys = getNumKeys(leaf);
            int idx = findKeyInLeaf(leaf, key, numKeys);
            if (idx >= 0 && idx < numKeys && getLeafKey(leaf, idx) == key) {
                return getLeafValue(leaf, idx);
            }
            return null;
        } finally {
            bufferPool.unpinPage(leafPageId, false);
        }
    }

    /**
     * Insert or update key-value pair into the B+Tree.
     */
    public synchronized void put(long key, long value) throws IOException {
        if (rootPageId == Constants.INVALID_PAGE_ID) {
            createIfEmpty();
        }

        int leafPageId = findLeafPage(key);
        Page leaf = bufferPool.fetchPage(leafPageId);
        try {
            int numKeys = getNumKeys(leaf);
            int idx = findKeyInLeaf(leaf, key, numKeys);
            if (idx < numKeys && getLeafKey(leaf, idx) == key) {
                // Key exists: update in place
                setLeafValue(leaf, idx, value);
                bufferPool.unpinPage(leafPageId, true);
                return;
            }

            // Insert into leaf
            if (numKeys < MAX_LEAF_KEYS) {
                insertIntoLeaf(leaf, idx, key, value);
                bufferPool.unpinPage(leafPageId, true);
            } else {
                // Leaf overflow: split
                splitLeafAndInsert(leaf, idx, key, value);
            }
        } catch (Exception e) {
            bufferPool.unpinPage(leafPageId, false);
            throw e;
        }
    }

    /**
     * Delete key from the B+Tree. Returns true if removed, false if not found.
     */
    public synchronized boolean delete(long key) throws IOException {
        if (rootPageId == Constants.INVALID_PAGE_ID) {
            return false;
        }
        int leafPageId = findLeafPage(key);
        Page leaf = bufferPool.fetchPage(leafPageId);
        try {
            int numKeys = getNumKeys(leaf);
            int idx = findKeyInLeaf(leaf, key, numKeys);
            if (idx < numKeys && getLeafKey(leaf, idx) == key) {
                deleteFromLeaf(leaf, idx, numKeys);
                bufferPool.unpinPage(leafPageId, true);
                return true;
            }
            bufferPool.unpinPage(leafPageId, false);
            return false;
        } catch (Exception e) {
            bufferPool.unpinPage(leafPageId, false);
            throw e;
        }
    }

    /**
     * Range scan [startKey, endKey] inclusive.
     */
    public synchronized List<Entry> scan(long startKey, long endKey) throws IOException {
        List<Entry> result = new ArrayList<>();
        if (rootPageId == Constants.INVALID_PAGE_ID) {
            return result;
        }

        int leafPageId = findLeafPage(startKey);
        while (leafPageId != Constants.INVALID_PAGE_ID) {
            Page leaf = bufferPool.fetchPage(leafPageId);
            try {
                int numKeys = getNumKeys(leaf);
                int nextLeaf = Bytes.getInt(leaf.getData(), HEADER_NEXT_LEAF);
                boolean done = false;
                for (int i = 0; i < numKeys; i++) {
                    long k = getLeafKey(leaf, i);
                    if (k > endKey) {
                        done = true;
                        break;
                    }
                    if (k >= startKey) {
                        result.add(new Entry(k, getLeafValue(leaf, i)));
                    }
                }
                bufferPool.unpinPage(leafPageId, false);
                if (done) break;
                leafPageId = nextLeaf;
            } catch (Exception e) {
                bufferPool.unpinPage(leafPageId, false);
                throw e;
            }
        }
        return result;
    }

    private int findLeafPage(long key) throws IOException {
        int currId = rootPageId;
        while (true) {
            Page page = bufferPool.fetchPage(currId);
            try {
                boolean isLeaf = Bytes.getShort(page.getData(), HEADER_IS_LEAF) == PAGE_TYPE_LEAF;
                if (isLeaf) {
                    bufferPool.unpinPage(currId, false);
                    return currId;
                }
                int nextChild = findChildInInternal(page, key);
                bufferPool.unpinPage(currId, false);
                currId = nextChild;
            } catch (Exception e) {
                bufferPool.unpinPage(currId, false);
                throw e;
            }
        }
    }

    private int findChildInInternal(Page page, long key) {
        int numKeys = getNumKeys(page);
        int leftChild = Bytes.getInt(page.getData(), HEADER_SIZE);
        if (numKeys == 0) return leftChild;

        for (int i = 0; i < numKeys; i++) {
            long k = getInternalKey(page, i);
            if (key < k) {
                if (i == 0) return leftChild;
                return getInternalChild(page, i - 1);
            }
        }
        return getInternalChild(page, numKeys - 1);
    }

    private int findKeyInLeaf(Page leaf, long key, int numKeys) {
        int low = 0;
        int high = numKeys - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            long midVal = getLeafKey(leaf, mid);
            if (midVal < key) {
                low = mid + 1;
            } else if (midVal > key) {
                high = mid - 1;
            } else {
                return mid;
            }
        }
        return low;
    }

    private void insertIntoLeaf(Page leaf, int index, long key, long value) {
        int numKeys = getNumKeys(leaf);
        byte[] data = leaf.getData();
        int srcOffset = HEADER_SIZE + index * LEAF_ENTRY_SIZE;
        int destOffset = HEADER_SIZE + (index + 1) * LEAF_ENTRY_SIZE;
        int bytesToMove = (numKeys - index) * LEAF_ENTRY_SIZE;

        if (bytesToMove > 0) {
            System.arraycopy(data, srcOffset, data, destOffset, bytesToMove);
        }
        Bytes.putLong(data, srcOffset, key);
        Bytes.putLong(data, srcOffset + 8, value);
        Bytes.putShort(data, HEADER_NUM_KEYS, (short) (numKeys + 1));
    }

    private void deleteFromLeaf(Page leaf, int index, int numKeys) {
        byte[] data = leaf.getData();
        int srcOffset = HEADER_SIZE + (index + 1) * LEAF_ENTRY_SIZE;
        int destOffset = HEADER_SIZE + index * LEAF_ENTRY_SIZE;
        int bytesToMove = (numKeys - index - 1) * LEAF_ENTRY_SIZE;

        if (bytesToMove > 0) {
            System.arraycopy(data, srcOffset, data, destOffset, bytesToMove);
        }
        Bytes.putShort(data, HEADER_NUM_KEYS, (short) (numKeys - 1));
    }

    private void splitLeafAndInsert(Page oldLeaf, int insertIdx, long key, long value) throws IOException {
        Page newLeaf = bufferPool.newPage();
        if (newLeaf == null) {
            throw new IOException("Failed to allocate new page for leaf split");
        }
        int newLeafId = newLeaf.getPageId();
        int oldLeafId = oldLeaf.getPageId();
        int parentId = Bytes.getInt(oldLeaf.getData(), HEADER_PARENT);

        initLeafPage(newLeaf, parentId);

        // Gather all keys and values
        int total = MAX_LEAF_KEYS + 1;
        long[] keys = new long[total];
        long[] values = new long[total];

        int cur = 0;
        for (int i = 0; i < MAX_LEAF_KEYS; i++) {
            if (cur == insertIdx) {
                keys[cur] = key;
                values[cur] = value;
                cur++;
            }
            keys[cur] = getLeafKey(oldLeaf, i);
            values[cur] = getLeafValue(oldLeaf, i);
            cur++;
        }
        if (insertIdx == MAX_LEAF_KEYS) {
            keys[MAX_LEAF_KEYS] = key;
            values[MAX_LEAF_KEYS] = value;
        }

        int split = total / 2;

        // Old leaf gets 0..split-1
        Bytes.putShort(oldLeaf.getData(), HEADER_NUM_KEYS, (short) split);
        for (int i = 0; i < split; i++) {
            setLeafKey(oldLeaf, i, keys[i]);
            setLeafValue(oldLeaf, i, values[i]);
        }

        // New leaf gets split..total-1
        int newCount = total - split;
        Bytes.putShort(newLeaf.getData(), HEADER_NUM_KEYS, (short) newCount);
        for (int i = 0; i < newCount; i++) {
            setLeafKey(newLeaf, i, keys[split + i]);
            setLeafValue(newLeaf, i, values[split + i]);
        }

        // Link leaves
        int oldNext = Bytes.getInt(oldLeaf.getData(), HEADER_NEXT_LEAF);
        Bytes.putInt(oldLeaf.getData(), HEADER_NEXT_LEAF, newLeafId);
        Bytes.putInt(newLeaf.getData(), HEADER_NEXT_LEAF, oldNext);

        long promoteKey = keys[split];

        bufferPool.unpinPage(oldLeafId, true);
        bufferPool.unpinPage(newLeafId, true);

        insertIntoParent(oldLeafId, promoteKey, newLeafId, parentId);
    }

    private void insertIntoParent(int leftChildId, long key, int rightChildId, int parentId) throws IOException {
        if (leftChildId == rootPageId) {
            // Create a new root
            Page newRoot = bufferPool.newPage();
            if (newRoot == null) {
                throw new IOException("Failed to allocate new root page");
            }
            int newRootId = newRoot.getPageId();
            initInternalPage(newRoot, Constants.INVALID_PAGE_ID, leftChildId);
            insertIntoInternal(newRoot, 0, key, rightChildId);
            rootPageId = newRootId;

            // Update parent IDs in children
            updateParent(leftChildId, newRootId);
            updateParent(rightChildId, newRootId);

            bufferPool.unpinPage(newRootId, true);
            return;
        }

        Page parent = bufferPool.fetchPage(parentId);
        try {
            int numKeys = getNumKeys(parent);
            int idx = findInsertIdxInInternal(parent, key, numKeys);

            if (numKeys < MAX_INTERNAL_KEYS) {
                insertIntoInternal(parent, idx, key, rightChildId);
                updateParent(rightChildId, parentId);
                bufferPool.unpinPage(parentId, true);
            } else {
                splitInternalAndInsert(parent, idx, key, rightChildId);
            }
        } catch (Exception e) {
            bufferPool.unpinPage(parentId, false);
            throw e;
        }
    }

    private void updateParent(int childPageId, int newParentId) throws IOException {
        Page child = bufferPool.fetchPage(childPageId);
        try {
            Bytes.putInt(child.getData(), HEADER_PARENT, newParentId);
            bufferPool.unpinPage(childPageId, true);
        } catch (Exception e) {
            bufferPool.unpinPage(childPageId, false);
            throw e;
        }
    }

    private void insertIntoInternal(Page page, int index, long key, int rightChild) {
        int numKeys = getNumKeys(page);
        byte[] data = page.getData();
        int offsetBase = HEADER_SIZE + 4; // after leftmost child pointer
        int srcOffset = offsetBase + index * INTERNAL_ENTRY_SIZE;
        int destOffset = offsetBase + (index + 1) * INTERNAL_ENTRY_SIZE;
        int bytesToMove = (numKeys - index) * INTERNAL_ENTRY_SIZE;

        if (bytesToMove > 0) {
            System.arraycopy(data, srcOffset, data, destOffset, bytesToMove);
        }
        Bytes.putLong(data, srcOffset, key);
        Bytes.putInt(data, srcOffset + 8, rightChild);
        Bytes.putShort(data, HEADER_NUM_KEYS, (short) (numKeys + 1));
    }

    private void splitInternalAndInsert(Page oldParent, int insertIdx, long key, int rightChild) throws IOException {
        Page newParent = bufferPool.newPage();
        if (newParent == null) {
            throw new IOException("Failed to allocate new internal page for split");
        }
        int newParentId = newParent.getPageId();
        int oldParentId = oldParent.getPageId();
        int grandParentId = Bytes.getInt(oldParent.getData(), HEADER_PARENT);

        int total = MAX_INTERNAL_KEYS + 1;
        long[] keys = new long[total];
        int[] children = new int[total];

        int cur = 0;
        for (int i = 0; i < MAX_INTERNAL_KEYS; i++) {
            if (cur == insertIdx) {
                keys[cur] = key;
                children[cur] = rightChild;
                cur++;
            }
            keys[cur] = getInternalKey(oldParent, i);
            children[cur] = getInternalChild(oldParent, i);
            cur++;
        }
        if (insertIdx == MAX_INTERNAL_KEYS) {
            keys[MAX_INTERNAL_KEYS] = key;
            children[MAX_INTERNAL_KEYS] = rightChild;
        }

        int split = total / 2;
        long promotedKey = keys[split];
        int promotedChild = children[split];

        // Old internal keeps 0..split-1
        Bytes.putShort(oldParent.getData(), HEADER_NUM_KEYS, (short) split);
        for (int i = 0; i < split; i++) {
            setInternalKey(oldParent, i, keys[i]);
            setInternalChild(oldParent, i, children[i]);
        }

        // New internal starts with promotedChild as leftmost child
        initInternalPage(newParent, grandParentId, promotedChild);
        int newCount = total - split - 1;
        Bytes.putShort(newParent.getData(), HEADER_NUM_KEYS, (short) newCount);
        for (int i = 0; i < newCount; i++) {
            setInternalKey(newParent, i, keys[split + 1 + i]);
            setInternalChild(newParent, i, children[split + 1 + i]);
        }

        // Update child pages' parent pointer
        updateParent(promotedChild, newParentId);
        for (int i = 0; i < newCount; i++) {
            updateParent(children[split + 1 + i], newParentId);
        }

        bufferPool.unpinPage(oldParentId, true);
        bufferPool.unpinPage(newParentId, true);

        insertIntoParent(oldParentId, promotedKey, newParentId, grandParentId);
    }

    private int findInsertIdxInInternal(Page page, long key, int numKeys) {
        for (int i = 0; i < numKeys; i++) {
            if (key < getInternalKey(page, i)) {
                return i;
            }
        }
        return numKeys;
    }

    // Helper getters/setters
    private short getNumKeys(Page page) {
        return Bytes.getShort(page.getData(), HEADER_NUM_KEYS);
    }

    private long getLeafKey(Page page, int idx) {
        return Bytes.getLong(page.getData(), HEADER_SIZE + idx * LEAF_ENTRY_SIZE);
    }

    private void setLeafKey(Page page, int idx, long key) {
        Bytes.putLong(page.getData(), HEADER_SIZE + idx * LEAF_ENTRY_SIZE, key);
    }

    private long getLeafValue(Page page, int idx) {
        return Bytes.getLong(page.getData(), HEADER_SIZE + idx * LEAF_ENTRY_SIZE + 8);
    }

    private void setLeafValue(Page page, int idx, long val) {
        Bytes.putLong(page.getData(), HEADER_SIZE + idx * LEAF_ENTRY_SIZE + 8, val);
    }

    private long getInternalKey(Page page, int idx) {
        return Bytes.getLong(page.getData(), HEADER_SIZE + 4 + idx * INTERNAL_ENTRY_SIZE);
    }

    private void setInternalKey(Page page, int idx, long key) {
        Bytes.putLong(page.getData(), HEADER_SIZE + 4 + idx * INTERNAL_ENTRY_SIZE, key);
    }

    private int getInternalChild(Page page, int idx) {
        return Bytes.getInt(page.getData(), HEADER_SIZE + 4 + idx * INTERNAL_ENTRY_SIZE + 8);
    }

    private void setInternalChild(Page page, int idx, int childPageId) {
        Bytes.putInt(page.getData(), HEADER_SIZE + 4 + idx * INTERNAL_ENTRY_SIZE + 8, childPageId);
    }

    public static class Entry {
        public final long key;
        public final long value;

        public Entry(long key, long value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public String toString() {
            return "(" + key + " => " + value + ")";
        }
    }
}

