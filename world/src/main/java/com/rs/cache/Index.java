package com.rs.cache;

/**
 * Stub Index class. Will be wired to the modern cache system later.
 */
public final class Index {

    public final boolean newProtocol = false;

    public Index() {
    }

    public int getLastFileId(int archiveId) {
        return -1;
    }

    public int getLastArchiveId() {
        return -1;
    }

    public int getValidArchivesCount() {
        return 0;
    }

    public int getValidFilesCount(int archiveId) {
        return 0;
    }

    public boolean archiveExists(int archiveId) {
        return false;
    }

    public boolean fileExists(int archiveId, int fileId) {
        return false;
    }

    public int getArchiveId(String name) {
        return -1;
    }

    public byte[] getFile(int archiveId) {
        return null;
    }

    public byte[] getFile(int archiveId, int fileId) {
        return null;
    }

    public byte[] getFile(int archiveId, int fileId, int[] keys) {
        return null;
    }

    public boolean putFile(int archiveId, int fileId, byte[] data) {
        return false;
    }

    public void resetCachedFiles() {
        // Stub: no cached files to reset in shim
    }

    public int getCRC() {
        return 0;
    }
}
