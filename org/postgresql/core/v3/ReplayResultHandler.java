package org.postgresql.core.v3;

import org.postgresql.core.Field;
import org.postgresql.core.Query;
import org.postgresql.core.ResultCursor;
import org.postgresql.core.ResultHandler;

import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by tivv on 10/4/14.
 */
public class ReplayResultHandler implements ResultHandler{
    private final List<HandlerCommand> replayList = new ArrayList<HandlerCommand>();
    
    public synchronized void replay(ResultHandler destination) throws SQLException {
        for (HandlerCommand command: replayList) {
            command.replay(destination);
        }
        replayList.clear();
    }
    @Override
    public synchronized void handleResultRows(Query fromQuery, Field[] fields, List tuples, ResultCursor cursor) {
        replayList.add(new HandleResultRowsCommand(fromQuery, fields, tuples, cursor));
    }

    @Override
    public synchronized void handleCommandStatus(String status, int updateCount, long insertOID) {
        replayList.add(new HandleStatusCommand(status, updateCount, insertOID));
    }

    @Override
    public synchronized void handleWarning(SQLWarning warning) {
        replayList.add(new HandleWarningCommand(warning));
    }

    @Override
    public synchronized void handleError(SQLException error) {
        replayList.add(new HandleErrorCommand(error));
    }

    @Override
    public synchronized void handleCompletion() throws SQLException {
        replayList.add(new HandleCompletionCommand());
    }

    interface HandlerCommand {

        void replay(ResultHandler destination) throws SQLException;
    }

    private static class HandleResultRowsCommand implements HandlerCommand {
        private final Query fromQuery;
        private final Field[] fields;
        private final List tuples;
        private final ResultCursor cursor;

        public HandleResultRowsCommand(Query fromQuery, Field[] fields, List tuples, ResultCursor cursor) {
            this.fromQuery = fromQuery;
            this.fields = fields;
            this.tuples = tuples;
            this.cursor = cursor;
        }

        @Override
        public void replay(ResultHandler destination) {
            destination.handleResultRows(fromQuery, fields, tuples, cursor);
        }
    }

    private static class HandleStatusCommand implements HandlerCommand {
        private final String status;
        private final int updateCount;
        private final long insertOID;

        public HandleStatusCommand(String status, int updateCount, long insertOID) {
            this.status = status;
            this.updateCount = updateCount;
            this.insertOID = insertOID;
        }

        @Override
        public void replay(ResultHandler destination) {
            destination.handleCommandStatus(status, updateCount, insertOID);
        }
    }

    private static class HandleWarningCommand implements HandlerCommand {
        private final SQLWarning warning;

        public HandleWarningCommand(SQLWarning warning) {
            this.warning = warning;
        }

        @Override
        public void replay(ResultHandler destination) {
            destination.handleWarning(warning);
        }
    }

    private static class HandleErrorCommand implements HandlerCommand {
        private final SQLException error;

        public HandleErrorCommand(SQLException error) {
            this.error = error;
        }

        @Override
        public void replay(ResultHandler destination) {
            destination.handleError(error);
        }
    }

    private static class HandleCompletionCommand implements HandlerCommand {

        @Override
        public void replay(ResultHandler destination) throws SQLException {
            destination.handleCompletion();
        }
    }
}
