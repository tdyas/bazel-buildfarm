package build.buildfarm.common;

import java.io.IOException;
import java.io.InputStream;

public class RingBufferInputStream extends InputStream {
  private final byte[] buffer;
  int inIndex = 0;
  int outIndex = 0;
  boolean flipped = false;
  boolean shutdown = false; // ignores available data
  boolean closed = false;

  public RingBufferInputStream(int size) {
    buffer = new byte[size];
  }

  public synchronized void close() {
    closed = true;
  }

  public synchronized void shutdown() {
    close();
    shutdown = true;
    notify();
  }

  @Override
  public synchronized int available() {
    return inAvailable();
  }

  private int inAvailable() {
    if (!flipped) {
      return outIndex - inIndex;
    }
    return buffer.length - inIndex + outIndex;
  }

  private int outAvailable() {
    if (!flipped) {
      return buffer.length - outIndex;
    }
    return inIndex - outIndex;
  }

  private int waitForInAvailable() throws InterruptedException {
    while (!shutdown) {
      int len = inAvailable();
      if (len > 0) {
        return len;
      }
      if (len == 0 && closed) {
        return -1;
      }
      wait();
    }
    return -1;
  }

  private int waitForOutAvailable() throws InterruptedException {
    while (!closed && !shutdown) {
      int len = outAvailable();
      if (len > 0) {
        return len;
      }
      wait();
    }
    return 0;
  }

  @Override
  public int read() throws IOException {
    try {
      return readInterruptibly();
    } catch (InterruptedException e) {
      throw new IOException(e);
    }
  }

  @Override
  public int read(byte[] buf, int off, int len) throws IOException {
    try {
      return readInterruptibly(buf, off, len);
    } catch (InterruptedException e) {
      throw new IOException(e);
    }
  }

  private synchronized int readInterruptibly() throws InterruptedException {
    if (waitForInAvailable() <= 0) {
      return -1;
    }
    int b = buffer[inIndex];
    if (++inIndex == buffer.length) {
      inIndex = 0;
      flipped = false;
    }
    notify();
    return b;
  }

  private synchronized int readInterruptibly(byte[] buf, int off, int len)
      throws InterruptedException {
    int totalBytesRead = 0;
    while (!shutdown && len > 0 && inAvailable() > 0) {
      int bytesRead = readPartial(buf, off, len);
      if (bytesRead > 0) {
        off += bytesRead;
        len -= bytesRead;
        totalBytesRead += bytesRead;
      }
    }
    return totalBytesRead;
  }

  private int readPartial(byte[] buf, int off, int len)
      throws InterruptedException {
    if (len <= 0) {
      return 0;
    }
    int available = waitForInAvailable();
    if (available <= 0) {
      return available;
    }
    int bytesToRead = Math.min(available, len);
    if (flipped) {
      bytesToRead = Math.min(bytesToRead, buffer.length - inIndex);
    }
    System.arraycopy(buffer, inIndex, buf, off, bytesToRead);
    int indexAfterRead = inIndex + bytesToRead;
    if (indexAfterRead == buffer.length) {
      indexAfterRead = 0;
      flipped = false;
    }
    inIndex = indexAfterRead;
    notify();
    return bytesToRead;
  }

  public synchronized void write(byte[] buf) throws InterruptedException {
    int len = buf.length;
    int off = 0;
    while (!shutdown && !closed && len > 0) {
      int bytesWritten = writePartial(buf, off, len);
      if (bytesWritten > 0) {
        off += bytesWritten;
        len -= bytesWritten;
      }
    }
    if (len != 0) {
      throw new InterruptedException();
    }
  }

  private int writePartial(byte[] buf, int off, int len) throws InterruptedException {
    int available = waitForOutAvailable();
    if (available <= 0) {
      return available;
    }
    if (!flipped) {
      available = Math.min(available, buffer.length - outIndex);
    }
    int bytesToWrite = Math.min(available, len);
    System.arraycopy(buf, off, buffer, outIndex, bytesToWrite);
    int indexAfterWrite = outIndex + bytesToWrite;
    if (indexAfterWrite == buffer.length) {
      indexAfterWrite = 0;
      flipped = true;
    }
    outIndex = indexAfterWrite;
    notify();
    return bytesToWrite;
  }
}
