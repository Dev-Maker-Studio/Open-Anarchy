package com.rs.cache;

/**
 * Stub Store class. Will be wired to the modern cache system later.
 */
public final class Store {

    public final boolean newProtocol = false;
    private final Huffman huffman = new Huffman();

    public Store() {
    }

    // TODO: Wire to modern cache system
    public Index getIndex(IndexType index) {
        return new Index();
    }

    public Index[] getIndices() {
        return new Index[0];
    }

    public Huffman getHuffman() {
        return huffman;
    }
}
