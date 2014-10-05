package org.postgresql.core.v3;

import org.postgresql.util.PSQLException;

import java.io.IOException;

/**
 * Created by tivv on 10/4/14.
 */
public interface AsyncConnectionReader {
    void doAsyncRead() throws PSQLException;
}
