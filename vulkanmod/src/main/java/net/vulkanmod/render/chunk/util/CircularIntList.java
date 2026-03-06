package net.vulkanmod.render.chunk.util;

import org.apache.commons.lang3.Validate;

import java.util.Iterator;

public class CircularIntList {
    private final int size;
    private final int[] list;
    private int startIndex;

    private final OwnIterator iterator;
    private final RangeIterator rangeIterator;

    public CircularIntList(int size) {
        this.size = size;
        this.list = new int[size + 2];

        this.iterator = new OwnIterator();
        this.rangeIterator = new RangeIterator();
    }

    public void updateStartIdx(int startIndex) {
        int[] list = this.list;
        this.startIndex = startIndex;

        list[0] = -1;
        list[size + 1] = -1;

        int k = 1;
        for(int i = startIndex; i < size; ++i) {
            list[k] = i;
            ++k;
        }
        for(int i = 0; i < startIndex; ++i) {
            list[k] = i;
            ++k;
        }
    }

    public int getNext(int i) {
        return this.list[i + 1];
    }

    public int getPrevious(int i) {
        return this.list[i - 1];
    }

    public OwnIterator iterator() {
        return this.iterator;
    }

    public RangeIterator getRangeIterator(int startIndex, int endIndex) {
        this.rangeIterator.update(startIndex, endIndex);
        return this.rangeIterator;
    }

    public RangeIterator createRangeIterator() {
        return new RangeIterator();
    }

    public class OwnIterator implements Iterator<Integer> {
        private int currentIndex = 0;
        private final int maxIndex = size;

        @Override
        public boolean hasNext() {
            return currentIndex < maxIndex;
        }

        @Override
        public Integer next() {
            currentIndex++;
            return list[currentIndex];
        }

        public int getCurrentIndex() {
            return currentIndex;
        }

        public void restart() {
            this.currentIndex = 0;
        }
    }

    public class RangeIterator implements Iterator<Integer> {
        private int currentIndex;
        private int startIndex;
        private int endIndex;

        public void update(int startIndex, int endIndex) {
            Validate.isTrue(endIndex < list.length, "Beyond max size");
            this.startIndex = startIndex + 1;
            this.endIndex = endIndex + 1;

            this.restart();
        }

        @Override
        public boolean hasNext() {
            return currentIndex < endIndex;
        }

        @Override
        public Integer next() {
            currentIndex++;
            return list[currentIndex];
        }

        public int getCurrentIndex() {
            return currentIndex;
        }

        public void restart() {
            this.currentIndex = this.startIndex - 1;
        }
    }
}
