package org.postgresql.core.v3;

import org.postgresql.core.Field;
import org.postgresql.core.Query;
import org.postgresql.core.ResultCursor;
import org.postgresql.core.ResultHandler;

import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.List;

/**
 * Created by tivv on 10/4/14.
 */
public class SkipResultsHandler implements ResultHandler {
    @Override
    public void handleResultRows(Query fromQuery, Field[] fields, List tuples, ResultCursor cursor) {

    }

    @Override
    public void handleCommandStatus(String status, int updateCount, long insertOID) {

    }

    @Override
    public void handleWarning(SQLWarning warning) {

    }

    @Override
    public void handleError(SQLException error) {

    }

    @Override
    public void handleCompletion() throws SQLException {

    }
}
