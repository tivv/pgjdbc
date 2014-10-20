/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2014, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.util;

import java.io.*;

/**
 * This stream reads from a temporary file that will be automatically deleted when the steam is closed.
 * Also the stream works as autoclose stream: it's closed automatically when reaches EOF.
 * In case when the stream is abandoned, it will remove the file when collected during GC.
 */
public class TmpFileInputStream extends FilterInputStream {
    private final File tmpFile;
    private boolean closed;

    public TmpFileInputStream(File tmpFile) throws FileNotFoundException {
        super(new FileInputStream(tmpFile));
        this.tmpFile = tmpFile;
    }

    @Override
    public int read() throws IOException {
        return checkClosed(super.read());
    }

    @Override
    public int read(byte[] b) throws IOException {
        return checkClosed(super.read(b));
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return checkClosed(super.read(b, off, len));
    }

    @Override
    public void close() throws IOException {
        super.close();
        if (!closed) {
            tmpFile.delete();
            closed = true;
        }
    }

    private int checkClosed(int read) {
        if (read < 0) {
            try
            {
                close();
            } catch (IOException e)
            {
                //Could not close "on the fly", just skip it
            }
        }
        return read;
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        close();
    }
}
