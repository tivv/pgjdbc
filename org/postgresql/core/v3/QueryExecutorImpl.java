/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2014, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core.v3;

import org.postgresql.core.*;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Properties;

import java.lang.ref.*;

import java.io.IOException;
import java.sql.*;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.GT;
import org.postgresql.copy.CopyOperation;

/**
 * QueryExecutor implementation for the V3 protocol.
 */
public class QueryExecutorImpl implements QueryExecutor {
    public QueryExecutorImpl(ProtocolConnectionImpl protoConnection, PGStream pgStream, Properties info, Logger logger) {
        this.protoConnection = protoConnection;
        this.pgStream = pgStream;
        this.logger = logger;
        this.protocolHelper = new ProtocolHelper(logger, pgStream, protoConnection);

        if (info.getProperty("allowEncodingChanges") != null) {
            this.allowEncodingChanges = Boolean.parseBoolean(info.getProperty("allowEncodingChanges"));
        } else {
            this.allowEncodingChanges = false;
        }
    }

    /**
     * Supplement to synchronization of public methods on current QueryExecutor.
     * 
     * Necessary for keeping the connection intact between calls to public methods
     * sharing a state such as COPY subprotocol. waitOnLock() must be called at
     * beginning of each connection access point.
     *
     * Public methods sharing that state must then be synchronized among themselves.
     * Normal method synchronization typically suffices for that.
     * 
     * See notes on related methods as well as currentCopy() below.
     */
    private Object lockedFor = null;

    /**
     * Obtain lock over this connection for given object, blocking to wait if necessary.
     * @param obtainer object that gets the lock. Normally current thread.
     * @throws PSQLException when already holding the lock or getting interrupted.
     */
    private void lock(Object obtainer) throws PSQLException {
        if( lockedFor == obtainer )
            throw new PSQLException(GT.tr("Tried to obtain lock while already holding it"), PSQLState.OBJECT_NOT_IN_STATE);
        waitOnLock();
        lockedFor = obtainer;
    }
    
    /**
     * Release lock on this connection presumably held by given object.
     * @param holder object that holds the lock. Normally current thread.
     * @throws PSQLException when this thread does not hold the lock
     */
    private void unlock(Object holder) throws PSQLException {
       if(lockedFor != holder)
           throw new PSQLException(GT.tr("Tried to break lock on database connection"), PSQLState.OBJECT_NOT_IN_STATE);
       lockedFor = null;
       this.notify();
    }

    /**
     * Wait until our lock is released.
     * Execution of a single synchronized method can then continue without further ado.
     * Must be called at beginning of each synchronized public method.
     */
    private void waitOnLock() throws PSQLException {
        while( lockedFor != null ) {
            try {
                this.wait();
            } catch(InterruptedException ie) {
                throw new PSQLException(GT.tr("Interrupted while waiting to obtain lock on database connection"), PSQLState.OBJECT_NOT_IN_STATE, ie);
            }
        }
    }
    
    /**
     * @param holder object assumed to hold the lock
     * @return whether given object actually holds the lock
     */
    boolean hasLock(Object holder) {
        return lockedFor == holder;
    }

    //
    // Query parsing
    //

    public Query createSimpleQuery(String sql) {
        return parseQuery(sql, false);
    }

    public Query createParameterizedQuery(String sql) {
        return parseQuery(sql, true);
    }

    private Query parseQuery(String query, boolean withParameters) {
        // Parse query and find parameter placeholders;
        // also break the query into separate statements.

        ArrayList statementList = new ArrayList();
        ArrayList fragmentList = new ArrayList(15);

        int fragmentStart = 0;
        int inParen = 0;

        boolean standardConformingStrings = protoConnection.getStandardConformingStrings();
        
        char []aChars = query.toCharArray();

        for (int i = 0; i < aChars.length; ++i)
        {
            switch (aChars[i])
            {
            case '\'': // single-quotes
                i = Parser.parseSingleQuotes(aChars, i, standardConformingStrings);
                break;

            case '"': // double-quotes
                i = Parser.parseDoubleQuotes(aChars, i);
                break;

            case '-': // possibly -- style comment
                i = Parser.parseLineComment(aChars, i);
                break;

            case '/': // possibly /* */ style comment
                i = Parser.parseBlockComment(aChars, i);
                break;
            
            case '$': // possibly dollar quote start
                i = Parser.parseDollarQuotes(aChars, i);
                break;

            case '(':
                inParen++;
                break;

            case ')':
                inParen--;
                break;

            case '?':
                if (withParameters)
                {
                    fragmentList.add(query.substring(fragmentStart, i));
                    fragmentStart = i + 1;
                }
                break;

            case ';':
                if (inParen == 0)
                {
                    fragmentList.add(query.substring(fragmentStart, i));
                    fragmentStart = i + 1;
                    if (fragmentList.size() > 1 || ((String)fragmentList.get(0)).trim().length() > 0)
                        statementList.add(fragmentList.toArray(new String[fragmentList.size()]));
                    fragmentList.clear();
                }
                break;

            default:
                break;
            }
        }

        fragmentList.add(query.substring(fragmentStart));
        if (fragmentList.size() > 1 || ((String)fragmentList.get(0)).trim().length() > 0)
            statementList.add(fragmentList.toArray(new String[fragmentList.size()]));

        if (statementList.isEmpty())  // Empty query.
            return EMPTY_QUERY;

        if (statementList.size() == 1)
        {
            // Only one statement.
            return new SimpleQuery((String[]) statementList.get(0), protoConnection);
        }

        // Multiple statements.
        SimpleQuery[] subqueries = new SimpleQuery[statementList.size()];
        int[] offsets = new int[statementList.size()];
        int offset = 0;
        for (int i = 0; i < statementList.size(); ++i)
        {
            String[] fragments = (String[]) statementList.get(i);
            offsets[i] = offset;
            subqueries[i] = new SimpleQuery(fragments, protoConnection);
            offset += fragments.length - 1;
        }

        return new CompositeQuery(subqueries, offsets);
    }

    //
    // Query execution
    //

    public synchronized void execute(Query query,
                                     ParameterList parameters,
                                     ResultHandler handler,
                                     int maxRows,
                                     int fetchSize,
                                     int flags)
    throws SQLException
    {
        waitOnLock();
        if (logger.logDebug())
        {
            logger.debug("simple execute, handler=" + handler +
                         ", maxRows=" + maxRows + ", fetchSize=" + fetchSize + ", flags=" + flags);
        }

        if (parameters == null)
            parameters = SimpleQuery.NO_PARAMETERS;

        boolean describeOnly = (QUERY_DESCRIBE_ONLY & flags) != 0;

        ((V3ParameterList)parameters).convertFunctionOutParameters();

        // Check parameters are all set..
        if (!describeOnly)
            ((V3ParameterList)parameters).checkAllParametersSet();

        try
        {
            QueryRunner queryRunner = new QueryRunner(this, protoConnection, pgStream, logger, allowEncodingChanges, flags);
            try
            {
                handler = sendQueryPreamble(queryRunner, handler, flags);
                ErrorTrackingResultHandler trackingHandler = new ErrorTrackingResultHandler(handler);
                queryCount = 0;
                queryRunner = sendQuery(queryRunner, (V3Query)query, (V3ParameterList)parameters, maxRows, fetchSize, flags, trackingHandler);
                protocolHelper.sendSync();
                queryRunner.processResults(handler, fetchSize);
            }
            catch (PGBindException se)
            {
                // There are three causes of this error, an
                // invalid total Bind message length, a
                // BinaryStream that cannot provide the amount
                // of data claimed by the length arugment, and
                // a BinaryStream that throws an Exception
                // when reading.
                //
                // We simply do not send the Execute message
                // so we can just continue on as if nothing
                // has happened.  Perhaps we need to
                // introduce an error here to force the
                // caller to rollback if there is a
                // transaction in progress?
                //
                protocolHelper.sendSync();
                queryRunner.processResults(handler, fetchSize);
                handler.handleError(new PSQLException(GT.tr("Unable to bind parameter values for statement."), PSQLState.INVALID_PARAMETER_VALUE, se.getIOException()));
            }
        }
        catch (IOException e)
        {
            protoConnection.close();
            handler.handleError(new PSQLException(GT.tr("An I/O error occurred while sending to the backend."), PSQLState.CONNECTION_FAILURE, e));
        }

        handler.handleCompletion();
    }

    // Deadlock avoidance:
    //
    // It's possible for the send and receive streams to get "deadlocked" against each other since
    // we do not have a separate thread. The scenario is this: we have two streams:
    //
    //   driver -> TCP buffering -> server
    //   server -> TCP buffering -> driver
    //
    // The server behaviour is roughly:
    //  while true:
    //   read message
    //   execute message
    //   write results
    //
    // If the server -> driver stream has a full buffer, the write will block.
    // If the driver is still writing when this happens, and the driver -> server
    // stream also fills up, we deadlock: the driver is blocked on write() waiting
    // for the server to read some more data, and the server is blocked on write()
    // waiting for the driver to read some more data.
    //
    // To avoid this, we guess at how many queries we can send before the server ->
    // driver stream's buffer is full (MAX_BUFFERED_QUERIES). This is the point where
    // the server blocks on write and stops reading data. If we reach this point, we
    // force a Sync message and read pending data from the server until ReadyForQuery,
    // then go back to writing more queries unless we saw an error.
    //
    // This is not 100% reliable -- it's only done in the batch-query case and only
    // at a reasonably high level (per query, not per message), and it's only an estimate
    // -- so it might break. To do it correctly in all cases would seem to require a
    // separate send or receive thread as we can only do the Sync-and-read-results
    // operation at particular points, and also as we don't really know how much data
    // the server is sending.

    // Assume 64k server->client buffering and 250 bytes response per query (conservative).
    private static final int MAX_BUFFERED_QUERIES = (64000 / 250);

    String allocateStatementName() {
        return "S_" + (nextUniqueID++);
    }

    // Helper handler that tracks error status.
    private static class ErrorTrackingResultHandler implements ResultHandler {
        private final ResultHandler delegateHandler;
        private boolean sawError = false;

        ErrorTrackingResultHandler(ResultHandler delegateHandler) {
            this.delegateHandler = delegateHandler;
        }

        public void handleResultRows(Query fromQuery, Field[] fields, List tuples, ResultCursor cursor) {
            delegateHandler.handleResultRows(fromQuery, fields, tuples, cursor);
        }

        public void handleCommandStatus(String status, int updateCount, long insertOID) {
            delegateHandler.handleCommandStatus(status, updateCount, insertOID);
        }

        public void handleWarning(SQLWarning warning) {
            delegateHandler.handleWarning(warning);
        }

        public void handleError(SQLException error) {
            sawError = true;
            delegateHandler.handleError(error);
        }

        public void handleCompletion() throws SQLException {
            delegateHandler.handleCompletion();
        }

        boolean hasErrors() {
            return sawError;
        }
    }

    public synchronized void execute(Query[] queries,
                                     ParameterList[] parameterLists,
                                     ResultHandler handler,
                                     int maxRows,
                                     int fetchSize,
                                     int flags)
    throws SQLException
    {
        waitOnLock();
        if (logger.logDebug())
        {
            logger.debug("batch execute " + queries.length + " queries, handler=" + handler +
                         ", maxRows=" + maxRows + ", fetchSize=" + fetchSize + ", flags=" + flags);
        }

        boolean describeOnly = (QUERY_DESCRIBE_ONLY & flags) != 0;
        // Check parameters and resolve OIDs.
        if (!describeOnly) {
            for (int i = 0; i < parameterLists.length; ++i)
            {
                if (parameterLists[i] != null)
                    ((V3ParameterList)parameterLists[i]).checkAllParametersSet();
            }
        }

        try
        {
            QueryRunner queryRunner = new QueryRunner(this, protoConnection, pgStream, logger, allowEncodingChanges, flags);
            handler = sendQueryPreamble(queryRunner, handler, flags);
            ErrorTrackingResultHandler trackingHandler = new ErrorTrackingResultHandler(handler);
            queryCount = 0;

            for (int i = 0; i < queries.length; ++i)
            {
                V3Query query = (V3Query)queries[i];
                V3ParameterList parameters = (V3ParameterList)parameterLists[i];
                if (parameters == null)
                    parameters = SimpleQuery.NO_PARAMETERS;

                queryRunner = sendQuery(queryRunner, query, parameters, maxRows, fetchSize, flags, trackingHandler);

                if (trackingHandler.hasErrors())
                    break;
            }

            if (!trackingHandler.hasErrors())
            {
                protocolHelper.sendSync();
                queryRunner.processResults(handler, fetchSize);
            }
        }
        catch (IOException e)
        {
            protoConnection.close();
            handler.handleError(new PSQLException(GT.tr("An I/O error occurred while sending to the backend."), PSQLState.CONNECTION_FAILURE, e));
        }

        handler.handleCompletion();
    }

    private ResultHandler sendQueryPreamble(QueryRunner queryRunner, final ResultHandler delegateHandler, int flags) throws IOException {
        // First, send CloseStatements for finalized SimpleQueries that had statement names assigned.
        processDeadParsedQueries();
        processDeadPortals();

        // Send BEGIN on first statement in transaction.
        if ((flags & QueryExecutor.QUERY_SUPPRESS_BEGIN) != 0 ||
                protoConnection.getTransactionState() != ProtocolConnection.TRANSACTION_IDLE)
            return delegateHandler;

        int beginFlags = QueryExecutor.QUERY_NO_METADATA;
        if ((flags & QueryExecutor.QUERY_ONESHOT) != 0) {
          beginFlags |= QueryExecutor.QUERY_ONESHOT;
        }
        sendOneQuery(queryRunner, beginTransactionQuery, SimpleQuery.NO_PARAMETERS, 0, 0, beginFlags);

        // Insert a handler that intercepts the BEGIN.
        return new ResultHandler() {
                   private boolean sawBegin = false;

                   public void handleResultRows(Query fromQuery, Field[] fields, List tuples, ResultCursor cursor) {
                       if (sawBegin)
                           delegateHandler.handleResultRows(fromQuery, fields, tuples, cursor);
                   }

                   public void handleCommandStatus(String status, int updateCount, long insertOID) {
                       if (!sawBegin)
                       {
                           sawBegin = true;
                           if (!status.equals("BEGIN"))
                               handleError(new PSQLException(GT.tr("Expected command status BEGIN, got {0}.", status),
                                                             PSQLState.PROTOCOL_VIOLATION));
                       }
                       else
                       {
                           delegateHandler.handleCommandStatus(status, updateCount, insertOID);
                       }
                   }

                   public void handleWarning(SQLWarning warning) {
                       delegateHandler.handleWarning(warning);
                   }

                   public void handleError(SQLException error) {
                       delegateHandler.handleError(error);
                   }

                   public void handleCompletion() throws SQLException{
                       delegateHandler.handleCompletion();
                   }
               };
    }

    //
    // Fastpath
    //

    public synchronized byte[]
    fastpathCall(int fnid, ParameterList parameters, boolean suppressBegin) throws SQLException {
        waitOnLock();
        if (!suppressBegin)
        {
            doSubprotocolBegin();
        }
        try
        {
            sendFastpathCall(fnid, (SimpleParameterList)parameters);
            return receiveFastpathResult();
        }
        catch (IOException ioe)
        {
            protoConnection.close();
            throw new PSQLException(GT.tr("An I/O error occurred while sending to the backend."), PSQLState.CONNECTION_FAILURE, ioe);
        }
    }

    public void doSubprotocolBegin() throws SQLException {
        if (protoConnection.getTransactionState() == ProtocolConnection.TRANSACTION_IDLE)
        {

            if (logger.logDebug())
                logger.debug("Issuing BEGIN before fastpath or copy call.");

            ResultHandler handler = new ResultHandler() {
                                        private boolean sawBegin = false;
                                        private SQLException sqle = null;

                                        public void handleResultRows(Query fromQuery, Field[] fields, List tuples, ResultCursor cursor) {
                                        }

                                        public void handleCommandStatus(String status, int updateCount, long insertOID) {
                                            if (!sawBegin)
                                            {
                                                if (!status.equals("BEGIN"))
                                                    handleError(new PSQLException(GT.tr("Expected command status BEGIN, got {0}.", status),
                                                                                  PSQLState.PROTOCOL_VIOLATION));
                                                sawBegin = true;
                                            }
                                            else
                                            {
                                                handleError(new PSQLException(GT.tr("Unexpected command status: {0}.", status),
                                                                              PSQLState.PROTOCOL_VIOLATION));
                                            }
                                        }

                                        public void handleWarning(SQLWarning warning) {
                                            // we don't want to ignore warnings and it would be tricky
                                            // to chain them back to the connection, so since we don't
                                            // expect to get them in the first place, we just consider
                                            // them errors.
                                            handleError(warning);
                                        }

                                        public void handleError(SQLException error) {
                                            if (sqle == null)
                                            {
                                                sqle = error;
                                            }
                                            else
                                            {
                                                sqle.setNextException(error);
                                            }
                                        }

                                        public void handleCompletion() throws SQLException{
                                            if (sqle != null)
                                                throw sqle;
                                        }
                                    };

            try
            {
                QueryRunner queryRunner = new QueryRunner(this, protoConnection, pgStream, logger, allowEncodingChanges, 0);
                sendOneQuery(queryRunner, beginTransactionQuery, SimpleQuery.NO_PARAMETERS, 0, 0, QueryExecutor.QUERY_NO_METADATA);
                protocolHelper.sendSync();
                queryRunner.processResults(handler, 0);
            }
            catch (IOException ioe)
            {
                throw new PSQLException(GT.tr("An I/O error occurred while sending to the backend."), PSQLState.CONNECTION_FAILURE, ioe);
            }
        }

    }

    public ParameterList createFastpathParameters(int count) {
        return new SimpleParameterList(count, protoConnection);
    }

    private void sendFastpathCall(int fnid, SimpleParameterList params) throws SQLException, IOException {
        if (logger.logDebug())
            logger.debug(" FE=> FunctionCall(" + fnid + ", " + params.getParameterCount() + " params)");

        //
        // Total size = 4 (length)
        //            + 4 (function OID)
        //            + 2 (format code count) + N * 2 (format codes)
        //            + 2 (parameter count) + encodedSize (parameters)
        //            + 2 (result format)

        int paramCount = params.getParameterCount();
        int encodedSize = 0;
        for (int i = 1; i <= paramCount; ++i)
        {
            if (params.isNull(i))
                encodedSize += 4;
            else
                encodedSize += 4 + params.getV3Length(i);
        }


        pgStream.SendChar('F');
        pgStream.SendInteger4(4 + 4 + 2 + 2 * paramCount + 2 + encodedSize + 2);
        pgStream.SendInteger4(fnid);
        pgStream.SendInteger2(paramCount);
        for (int i = 1; i <= paramCount; ++i)
            pgStream.SendInteger2(params.isBinary(i) ? 1 : 0);
        pgStream.SendInteger2(paramCount);
        for (int i = 1; i <= paramCount; i++)
        {
            if (params.isNull(i))
            {
                pgStream.SendInteger4( -1);
            }
            else
            {
                pgStream.SendInteger4(params.getV3Length(i));   // Parameter size
                params.writeV3Value(i, pgStream);
            }
        }
        pgStream.SendInteger2(1); // Binary result format
        pgStream.flush();
    }

    public synchronized void processNotifies() throws SQLException {
        waitOnLock();
        // Asynchronous notifies only arrive when we are not in a transaction
        if (protoConnection.getTransactionState() != ProtocolConnection.TRANSACTION_IDLE)
            return;

        try {
            while (pgStream.hasMessagePending()) {
                int c = pgStream.ReceiveChar();
                switch (c) {
                case 'A':  // Asynchronous Notify
                    protocolHelper.receiveAsyncNotify();
                    break;
                case 'E':  // Error Response (response to pretty much everything; backend then skips until Sync)
                    throw protocolHelper.receiveErrorResponse();
                    // break;
                case 'N':  // Notice Response (warnings / info)
                    SQLWarning warning = protocolHelper.receiveNoticeResponse();
                    protoConnection.addWarning(warning);
                    break;
                default:
                    throw new PSQLException(GT.tr("Unknown Response Type {0}.", new Character((char) c)), PSQLState.CONNECTION_FAILURE);
                }
            }
        } catch (IOException ioe) {
            throw new PSQLException(GT.tr("An I/O error occurred while sending to the backend."), PSQLState.CONNECTION_FAILURE, ioe);
        }
    }
    
    private byte[] receiveFastpathResult() throws IOException, SQLException {
        boolean endQuery = false;
        SQLException error = null;
        byte[] returnValue = null;

        while (!endQuery)
        {
            int c = pgStream.ReceiveChar();
            switch (c)
            {
            case 'A':  // Asynchronous Notify
                protocolHelper.receiveAsyncNotify();
                break;

            case 'E':  // Error Response (response to pretty much everything; backend then skips until Sync)
                SQLException newError = protocolHelper.receiveErrorResponse();
                if (error == null)
                    error = newError;
                else
                    error.setNextException(newError);
                // keep processing
                break;

            case 'N':  // Notice Response (warnings / info)
                SQLWarning warning = protocolHelper.receiveNoticeResponse();
                protoConnection.addWarning(warning);
                break;

            case 'Z':    // Ready For Query (eventual response to Sync)
                protocolHelper.receiveRFQ();
                endQuery = true;
                break;

            case 'V':  // FunctionCallResponse
                int msgLen = pgStream.ReceiveInteger4();
                int valueLen = pgStream.ReceiveInteger4();

                if (logger.logDebug())
                    logger.debug(" <=BE FunctionCallResponse(" + valueLen + " bytes)");

                if (valueLen != -1)
                {
                    byte buf[] = new byte[valueLen];
                    pgStream.Receive(buf, 0, valueLen);
                    returnValue = buf;
                }

                break;

            default:
                throw new PSQLException(GT.tr("Unknown Response Type {0}.", new Character((char) c)), PSQLState.CONNECTION_FAILURE);
            }

        }

        // did we get an error during this query?
        if (error != null)
            throw error;

        return returnValue;
    }

    //
    // Copy subprotocol implementation
    //

    /**
     * Sends given query to BE to start, initialize and lock connection for a CopyOperation.
     * @param sql COPY FROM STDIN / COPY TO STDOUT statement
     * @return CopyIn or CopyOut operation object
     * @throws SQLException on failure
     */
    public synchronized CopyOperation startCopy(String sql, boolean suppressBegin) throws SQLException {
        waitOnLock();
        if (!suppressBegin) {
            doSubprotocolBegin();
        }
        byte buf[] = Utils.encodeUTF8(sql);

        try {
            if (logger.logDebug())
                logger.debug(" FE=> Query(CopyStart)");

            pgStream.SendChar('Q');
            pgStream.SendInteger4(buf.length + 4 + 1);
            pgStream.Send(buf);
            pgStream.SendChar(0);
            pgStream.flush();

            return processCopyResults(null, true); // expect a CopyInResponse or CopyOutResponse to our query above
        } catch(IOException ioe) {
            throw new PSQLException(GT.tr("Database connection failed when starting copy"), PSQLState.CONNECTION_FAILURE, ioe);
        }
    }

    /**
     * Locks connection and calls initializer for a new CopyOperation
     * Called via startCopy -> processCopyResults
     * @param op an unitialized CopyOperation
     * @throws SQLException on locking failure
     * @throws IOException on database connection failure
     */
    private synchronized void initCopy(CopyOperationImpl op) throws SQLException, IOException {
        pgStream.ReceiveInteger4(); // length not used
        int rowFormat = pgStream.ReceiveChar();
        int numFields = pgStream.ReceiveInteger2();
        int[] fieldFormats = new int[numFields];

        for(int i=0; i<numFields; i++)
            fieldFormats[i] = pgStream.ReceiveInteger2();

        lock(op);
        op.init(this, rowFormat, fieldFormats);
    }

    /**
     * Finishes a copy operation and unlocks connection discarding any exchanged data.
     * @param op the copy operation presumably currently holding lock on this connection
     * @throws SQLException on any additional failure
     */
    public void cancelCopy(CopyOperationImpl op) throws SQLException {
        if(!hasLock(op))
            throw new PSQLException(GT.tr("Tried to cancel an inactive copy operation"), PSQLState.OBJECT_NOT_IN_STATE);

        SQLException error = null;
        int errors = 0;

        try {
            if(op instanceof CopyInImpl) {
                synchronized (this) {
                    if (logger.logDebug()) {
                        logger.debug("FE => CopyFail");
                    }
                    final byte[] msg = Utils.encodeUTF8("Copy cancel requested");
                    pgStream.SendChar('f'); // CopyFail
                    pgStream.SendInteger4(5 + msg.length);
                    pgStream.Send(msg);
                    pgStream.SendChar(0);
                    pgStream.flush();
                    do {
                        try {
                            processCopyResults(op, true); // discard rest of input
                        } catch(SQLException se) { // expected error response to failing copy
                            errors++;
                            if( error != null ) {
                                SQLException e = se, next;
                                while( (next = e.getNextException()) != null )
                                    e = next;
                                e.setNextException(error);
                            }
                            error = se; 
                        }
                    } while(hasLock(op));
                }
            } else if (op instanceof CopyOutImpl) {
                protoConnection.sendQueryCancel();
            }

        } catch(IOException ioe) {
            throw new PSQLException(GT.tr("Database connection failed when canceling copy operation"), PSQLState.CONNECTION_FAILURE, ioe);
        }

        if (op instanceof CopyInImpl) {
            if(errors < 1) {
                throw new PSQLException(GT.tr("Missing expected error response to copy cancel request"), PSQLState.COMMUNICATION_ERROR);
            } else if(errors > 1) {
                throw new PSQLException(GT.tr("Got {0} error responses to single copy cancel request", String.valueOf(errors)), PSQLState.COMMUNICATION_ERROR, error);
            }
        }
    }

    /**
     * Finishes writing to copy and unlocks connection
     * @param op the copy operation presumably currently holding lock on this connection
     * @return number of rows updated for server versions 8.2 or newer
     * @throws SQLException on failure
     */
    public synchronized long endCopy(CopyInImpl op) throws SQLException {
        if(!hasLock(op))
                throw new PSQLException(GT.tr("Tried to end inactive copy"), PSQLState.OBJECT_NOT_IN_STATE);

        try {
            if (logger.logDebug())
                logger.debug(" FE=> CopyDone");

            pgStream.SendChar('c'); // CopyDone
            pgStream.SendInteger4(4);
            pgStream.flush();

            processCopyResults(op, true);
            return op.getHandledRowCount();
        } catch(IOException ioe) {
            throw new PSQLException(GT.tr("Database connection failed when ending copy"), PSQLState.CONNECTION_FAILURE, ioe);
        }
    }

    /**
     * Sends data during a live COPY IN operation. Only unlocks the connection if server
     * suddenly returns CommandComplete, which should not happen
     * @param op the CopyIn operation presumably currently holding lock on this connection
     * @param data bytes to send
     * @param off index of first byte to send (usually 0)
     * @param siz number of bytes to send (usually data.length)
     * @throws SQLException on failure
     */
    public synchronized void writeToCopy(CopyInImpl op, byte[] data, int off, int siz) throws SQLException {
        if(!hasLock(op))
            throw new PSQLException(GT.tr("Tried to write to an inactive copy operation"), PSQLState.OBJECT_NOT_IN_STATE);

        if (logger.logDebug())
            logger.debug(" FE=> CopyData(" + siz + ")");

        try {
            pgStream.SendChar('d');
            pgStream.SendInteger4(siz + 4);
            pgStream.Send(data, off, siz);

            processCopyResults(op, false); // collect any pending notifications without blocking
        } catch(IOException ioe) {
            throw new PSQLException(GT.tr("Database connection failed when writing to copy"), PSQLState.CONNECTION_FAILURE, ioe);
        }
    }

    public synchronized void flushCopy(CopyInImpl op) throws SQLException {
        if(!hasLock(op))
            throw new PSQLException(GT.tr("Tried to write to an inactive copy operation"), PSQLState.OBJECT_NOT_IN_STATE);

        try {
            pgStream.flush();
            processCopyResults(op, false); // collect any pending notifications without blocking
        } catch(IOException ioe) {
            throw new PSQLException(GT.tr("Database connection failed when writing to copy"), PSQLState.CONNECTION_FAILURE, ioe);
        }
    }

    /**
     * Blocks to wait for a row of data to be received from server on an active copy operation
     * Connection gets unlocked by processCopyResults() at end of operation
     * @param op the copy operation presumably currently holding lock on this connection
     * @throws SQLException on any failure
     */
    synchronized void readFromCopy(CopyOutImpl op) throws SQLException {
        if(!hasLock(op))
            throw new PSQLException(GT.tr("Tried to read from inactive copy"), PSQLState.OBJECT_NOT_IN_STATE);

        try {
            processCopyResults(op, true); // expect a call to handleCopydata() to store the data
        } catch(IOException ioe) {
            throw new PSQLException(GT.tr("Database connection failed when reading from copy"), PSQLState.CONNECTION_FAILURE, ioe);
        }
    }

    /**
     * Handles copy sub protocol responses from server.
     * Unlocks at end of sub protocol,
     * so operations on pgStream or QueryExecutor are not allowed in a method after calling this!
     * @param block whether to block waiting for input
     * @return 
     *  CopyIn when COPY FROM STDIN starts;
     *  CopyOut when COPY TO STDOUT starts;
     *  null when copy ends;
     *  otherwise, the operation given as parameter.
     * @throws SQLException in case of misuse
     * @throws IOException from the underlying connection
     */
    CopyOperationImpl processCopyResults(CopyOperationImpl op, boolean block) throws SQLException, IOException {

        boolean endReceiving = false;
        SQLException error = null, errors = null;
        int len;

        while( !endReceiving && (block || pgStream.hasMessagePending()) ) {

            // There is a bug in the server's implementation of the copy
            // protocol.  It returns command complete immediately upon
            // receiving the EOF marker in the binary protocol,
            // potentially before we've issued CopyDone.  When we are not
            // blocking, we don't think we are done, so we hold off on
            // processing command complete and any subsequent messages
            // until we actually are done with the copy.
            //
            if (!block) {
                int c = pgStream.PeekChar();
                if (c == 'C') // CommandComplete
                {
                    if (logger.logDebug())
                        logger.debug(" <=BE CommandStatus, Ignored until CopyDone");
                    break;
                }
            }

            int c = pgStream.ReceiveChar();
            switch(c) {

            case 'A': // Asynchronous Notify

                if (logger.logDebug())
                    logger.debug(" <=BE Asynchronous Notification while copying");

                protocolHelper.receiveAsyncNotify();
                break;

            case 'N': // Notice Response

                if (logger.logDebug())
                    logger.debug(" <=BE Notification while copying");

                protoConnection.addWarning(protocolHelper.receiveNoticeResponse());
                break;

            case 'C': // Command Complete

                String status = protocolHelper.receiveCommandStatus();

                try {
                    if(op == null)
                        throw new PSQLException(GT.tr("Received CommandComplete ''{0}'' without an active copy operation", status), PSQLState.OBJECT_NOT_IN_STATE);
                    op.handleCommandStatus(status);
                } catch(SQLException se) {
                    error = se;
                }

                block = true;
                break;

            case 'E': // ErrorMessage (expected response to CopyFail)

                error = protocolHelper.receiveErrorResponse();
                // We've received the error and we now expect to receive
                // Ready for query, but we must block because it might still be
                // on the wire and not here yet.
                block = true;
                break;

            case 'G':  // CopyInResponse
                
                if (logger.logDebug())
                    logger.debug(" <=BE CopyInResponse");

                if(op != null)
                    error = new PSQLException(GT.tr("Got CopyInResponse from server during an active {0}", op.getClass().getName()), PSQLState.OBJECT_NOT_IN_STATE);

                op = new CopyInImpl();
                initCopy(op);
                endReceiving = true;
                break;
                
            case 'H':  // CopyOutResponse
                
                if (logger.logDebug())
                    logger.debug(" <=BE CopyOutResponse");

                if(op != null)
                    error = new PSQLException(GT.tr("Got CopyOutResponse from server during an active {0}", op.getClass().getName()), PSQLState.OBJECT_NOT_IN_STATE);

                op = new CopyOutImpl();
                initCopy(op);
                endReceiving = true;
                break;

            case 'd': // CopyData

                if (logger.logDebug())
                    logger.debug(" <=BE CopyData");

                len = pgStream.ReceiveInteger4() - 4;
                byte[] buf = pgStream.Receive(len);
                if(op == null) {
                    error = new PSQLException(GT.tr("Got CopyData without an active copy operation"), PSQLState.OBJECT_NOT_IN_STATE);
                } else if (!(op instanceof CopyOutImpl)) {
                    error = new PSQLException(GT.tr("Unexpected copydata from server for {0}",
                            op.getClass().getName()), PSQLState.COMMUNICATION_ERROR);
                } else {
                    ((CopyOutImpl)op).handleCopydata(buf);
                }
                endReceiving = true;
                break;

            case 'c': // CopyDone (expected after all copydata received)

                if (logger.logDebug())
                    logger.debug(" <=BE CopyDone");
                
                len = pgStream.ReceiveInteger4() - 4;
                if(len > 0)
                    pgStream.Receive(len); // not in specification; should never appear

                if(!(op instanceof CopyOutImpl))
                    error = new PSQLException("Got CopyDone while not copying from server", PSQLState.OBJECT_NOT_IN_STATE);
                
                // keep receiving since we expect a CommandComplete
                block = true;
                break;
            case 'S':    // Parameter Status
            {
                int l_len = pgStream.ReceiveInteger4();
                String name = pgStream.ReceiveString();
                String value = pgStream.ReceiveString();
                if (logger.logDebug())
                    logger.debug(" <=BE ParameterStatus(" + name + " = " + value + ")");

                if (name.equals("client_encoding") && !value.equalsIgnoreCase("UTF8") && !allowEncodingChanges)
                {
                    protoConnection.close(); // we're screwed now; we can't trust any subsequent string.
                    error = new PSQLException(GT.tr("The server''s client_encoding parameter was changed to {0}. The JDBC driver requires client_encoding to be UTF8 for correct operation.", value), PSQLState.CONNECTION_FAILURE);
                    endReceiving = true;
                }

                if (name.equals("DateStyle") && !value.startsWith("ISO,"))
                {
                    protoConnection.close(); // we're screwed now; we can't trust any subsequent date.
                    error = new PSQLException(GT.tr("The server''s DateStyle parameter was changed to {0}. The JDBC driver requires DateStyle to begin with ISO for correct operation.", value), PSQLState.CONNECTION_FAILURE);
                    endReceiving = true;
                }
                
                if (name.equals("standard_conforming_strings"))
                {
                    if (value.equals("on"))
                        protoConnection.setStandardConformingStrings(true);
                    else if (value.equals("off"))
                        protoConnection.setStandardConformingStrings(false);
                    else
                    {
                        protoConnection.close(); // we're screwed now; we don't know how to escape string literals
                        error = new PSQLException(GT.tr("The server''s standard_conforming_strings parameter was reported as {0}. The JDBC driver expected on or off.", value), PSQLState.CONNECTION_FAILURE);
                        endReceiving = true;
                    }
                }
            }
            break;

            case 'Z': // ReadyForQuery: After FE:CopyDone => BE:CommandComplete

                protocolHelper.receiveRFQ();
                if(hasLock(op))
                    unlock(op);
                op = null;
                endReceiving = true;
                break;

            // If the user sends a non-copy query, we've got to handle some additional things.
            //
            case 'T':  // Row Description (response to Describe)
                if (logger.logDebug())
                    logger.debug(" <=BE RowDescription (during copy ignored)");


                protocolHelper.skipMessage();
                break;

            case 'D':  // DataRow
                if (logger.logDebug())
                    logger.debug(" <=BE DataRow (during copy ignored)");

                protocolHelper.skipMessage();
                break;

            default:
                throw new IOException(GT.tr("Unexpected packet type during copy: {0}", Integer.toString(c)));
            }

            // Collect errors into a neat chain for completeness
            if(error != null) {
                if(errors != null)
                    error.setNextException(errors);
                errors = error;
                error = null;
            }
        }

        if(errors != null)
            throw errors;

        return op;
    }

    /*
     * Send a query to the backend.
     */
    private QueryRunner sendQuery(QueryRunner queryRunner, V3Query query, V3ParameterList parameters, int maxRows, int fetchSize, int flags, ErrorTrackingResultHandler trackingHandler) throws IOException, SQLException {
        // Now the query itself.
        SimpleQuery[] subqueries = query.getSubqueries();
        SimpleParameterList[] subparams = parameters.getSubparams();
        boolean disallowBatching = (flags & QueryExecutor.QUERY_DISALLOW_BATCHING) != 0;

        if (subqueries == null)
        {
            ++queryCount;
            if (disallowBatching || queryCount >= MAX_BUFFERED_QUERIES)
            {
                protocolHelper.sendSync();
                queryRunner.processResults(trackingHandler, fetchSize);

                queryCount = 0;
                queryRunner = queryRunner.getRunnerForNextQuery();
            }

             // If we saw errors, don't send anything more.
            if (!trackingHandler.hasErrors())
                sendOneQuery(queryRunner, (SimpleQuery)query, (SimpleParameterList)parameters, maxRows, fetchSize, flags);
        }
        else
        {
            for (int i = 0; i < subqueries.length; ++i)
            {
                ++queryCount;
                if (disallowBatching || queryCount >= MAX_BUFFERED_QUERIES)
                {
                    protocolHelper.sendSync();
                    queryRunner.processResults(trackingHandler, fetchSize);
                    queryRunner = queryRunner.getRunnerForNextQuery();

                    // If we saw errors, don't send anything more.
                    if (trackingHandler.hasErrors())
                        break;

                    queryCount = 0;
                }

                // In the situation where parameters is already
                // NO_PARAMETERS it cannot know the correct
                // number of array elements to return in the
                // above call to getSubparams(), so it must
                // return null which we check for here.
                //
                SimpleParameterList subparam = SimpleQuery.NO_PARAMETERS;
                if (subparams != null)
                {
                    subparam = subparams[i];
                }
                sendOneQuery(queryRunner, subqueries[i], subparam, maxRows, fetchSize, flags);
            }
        }
        return queryRunner;
    }

    //
    // Message sending
    //

    private void sendClosePortal(String portalName) throws IOException {
        //
        // Send Close.
        //

        if (logger.logDebug())
        {
            logger.debug(" FE=> ClosePortal(" + portalName + ")");
        }

        byte[] encodedPortalName = (portalName == null ? null : Utils.encodeUTF8(portalName));
        int encodedSize = (encodedPortalName == null ? 0 : encodedPortalName.length);

        // Total size = 4 (size field) + 1 (close type, 'P') + 1 + N (portal name)
        pgStream.SendChar('C');              // Close
        pgStream.SendInteger4(4 + 1 + 1 + encodedSize);  // message size
        pgStream.SendChar('P');              // Close (Portal)
        if (encodedPortalName != null)
            pgStream.Send(encodedPortalName);
        pgStream.SendChar(0);                // unnamed portal
    }

    private void sendCloseStatement(String statementName) throws IOException {
        //
        // Send Close.
        //

        if (logger.logDebug())
        {
            logger.debug(" FE=> CloseStatement(" + statementName + ")");
        }

        byte[] encodedStatementName = Utils.encodeUTF8(statementName);

        // Total size = 4 (size field) + 1 (close type, 'S') + N + 1 (statement name)
        pgStream.SendChar('C');              // Close
        pgStream.SendInteger4(4 + 1 + encodedStatementName.length + 1);  // message size
        pgStream.SendChar('S');              // Close (Statement)
        pgStream.Send(encodedStatementName); // statement to close
        pgStream.SendChar(0);                // statement name terminator
    }

    // sendOneQuery sends a single statement via the extended query protocol.
    // Per the FE/BE docs this is essentially the same as how a simple query runs
    // (except that it generates some extra acknowledgement messages, and we
    // can send several queries before doing the Sync)
    //
    //   Parse     S_n from "query string with parameter placeholders"; skipped if already done previously or if oneshot
    //   Bind      C_n from S_n plus parameters (or from unnamed statement for oneshot queries)
    //   Describe  C_n; skipped if caller doesn't want metadata
    //   Execute   C_n with maxRows limit; maxRows = 1 if caller doesn't want results
    // (above repeats once per call to sendOneQuery)
    //   Sync      (sent by caller)
    //
    private void sendOneQuery(QueryRunner queryRunner, SimpleQuery query, SimpleParameterList params, int maxRows, int fetchSize, int flags) throws IOException {
        // nb: if we decide to use a portal (usePortal == true) we must also use a named statement
        // (oneShot == false) as otherwise the portal will be closed under us unexpectedly when
        // the unnamed statement is next reused.

        boolean noResults = (flags & QueryExecutor.QUERY_NO_RESULTS) != 0;
        boolean noMeta = (flags & QueryExecutor.QUERY_NO_METADATA) != 0;
        boolean describeOnly = (flags & QueryExecutor.QUERY_DESCRIBE_ONLY) != 0;
        boolean usePortal = (flags & QueryExecutor.QUERY_FORWARD_CURSOR) != 0 && !noResults && !noMeta && fetchSize > 0 && !describeOnly;
        boolean oneShot = (flags & QueryExecutor.QUERY_ONESHOT) != 0 && !usePortal;
        boolean noBinaryTransfer = (flags & QUERY_NO_BINARY_TRANSFER) != 0;

        // Work out how many rows to fetch in this pass.

        int rows;
        if (noResults)
        {
            rows = 1;             // We're discarding any results anyway, so limit data transfer to a minimum
        }
        else if (!usePortal)
        {
            rows = maxRows;       // Not using a portal -- fetchSize is irrelevant
        }
        else if (maxRows != 0 && fetchSize > maxRows)
        {
            rows = maxRows;       // fetchSize > maxRows, use maxRows (nb: fetchSize cannot be 0 if usePortal == true)
        }
        else
        {
            rows = fetchSize;     // maxRows > fetchSize
        }

        queryRunner.sendParse(query, params, oneShot);

        // Must do this after sendParse to pick up any changes to the
        // query's state.
        //
        boolean queryHasUnknown = query.hasUnresolvedTypes();
        boolean paramsHasUnknown = params.hasUnresolvedTypes();

        boolean describeStatement = describeOnly || (!oneShot && paramsHasUnknown && queryHasUnknown && !query.isStatementDescribed());

        if (!describeStatement && paramsHasUnknown && !queryHasUnknown)
        {
            int queryOIDs[] = query.getStatementTypes();
            int paramOIDs[] = params.getTypeOIDs();
            for (int i=0; i<paramOIDs.length; i++) {
                // Only supply type information when there isn't any
                // already, don't arbitrarily overwrite user supplied
                // type information.
                if (paramOIDs[i] == Oid.UNSPECIFIED) {
                    params.setResolvedType(i+1, queryOIDs[i]);
                }
            }
        }

        if (describeStatement) {
            queryRunner.sendDescribeStatement(query, params, describeOnly);
            if (describeOnly)
                return;
        }

        // Construct a new portal if needed.
        Portal portal = null;
        if (usePortal)
        {
            String portalName = "C_" + (nextUniqueID++);
            portal = new Portal(query, portalName);
        }

        queryRunner.sendBind(query, params, portal, noBinaryTransfer);

        // A statement describe will also output a RowDescription,
        // so don't reissue it here if we've already done so.
        //
        if (!noMeta && !describeStatement) {
            // don't send describe if we already have cached the
            // descriptionrow from previous executions
            if (query.getFields() == null) {
              queryRunner.sendDescribePortal(query, portal);
            }
        }

        queryRunner.sendExecute(query, portal, rows);
    }

    //
    // Garbage collection of parsed statements.
    //
    // When a statement is successfully parsed, registerParsedQuery is called.
    // This creates a PhantomReference referring to the "owner" of the statement
    // (the originating Query object) and inserts that reference as a key in
    // parsedQueryMap. The values of parsedQueryMap are the corresponding allocated
    // statement names. The originating Query object also holds a reference to the
    // PhantomReference.
    //
    // When the owning Query object is closed, it enqueues and clears the associated
    // PhantomReference.
    //
    // If the owning Query object becomes unreachable (see java.lang.ref javadoc) before
    // being closed, the corresponding PhantomReference is enqueued on
    // parsedQueryCleanupQueue. In the Sun JVM, phantom references are only enqueued
    // when a GC occurs, so this is not necessarily prompt but should eventually happen.
    //
    // Periodically (currently, just before query execution), the parsedQueryCleanupQueue
    // is polled. For each enqueued PhantomReference we find, we remove the corresponding
    // entry from parsedQueryMap, obtaining the name of the underlying statement in the
    // process. Then we send a message to the backend to deallocate that statement.
    //

    private final HashMap parsedQueryMap = new HashMap();
    private final ReferenceQueue parsedQueryCleanupQueue = new ReferenceQueue();

    void registerParsedQuery(SimpleQuery query, String statementName) {
        if (statementName == null)
            return ;

        PhantomReference cleanupRef = new PhantomReference(query, parsedQueryCleanupQueue);
        parsedQueryMap.put(cleanupRef, statementName);
        query.setCleanupRef(cleanupRef);
    }

    void processDeadParsedQueries() throws IOException {
        PhantomReference deadQuery;
        while ((deadQuery = (PhantomReference)parsedQueryCleanupQueue.poll()) != null)
        {
            String statementName = (String)parsedQueryMap.remove(deadQuery);
            sendCloseStatement(statementName);
            deadQuery.clear();
        }
    }

    //
    // Essentially the same strategy is used for the cleanup of portals.
    // Note that each Portal holds a reference to the corresponding Query
    // that generated it, so the Query won't be collected (and the statement
    // closed) until all the Portals are, too. This is required by the mechanics
    // of the backend protocol: when a statement is closed, all dependent portals
    // are also closed.
    //

    private final HashMap openPortalMap = new HashMap();
    private final ReferenceQueue openPortalCleanupQueue = new ReferenceQueue();

    void registerOpenPortal(Portal portal) {
        if (portal == null)
            return ; // Using the unnamed portal.

        String portalName = portal.getPortalName();
        PhantomReference cleanupRef = new PhantomReference(portal, openPortalCleanupQueue);
        openPortalMap.put(cleanupRef, portalName);
        portal.setCleanupRef(cleanupRef);
    }

    private void processDeadPortals() throws IOException {
        PhantomReference deadPortal;
        while ((deadPortal = (PhantomReference)openPortalCleanupQueue.poll()) != null)
        {
            String portalName = (String)openPortalMap.remove(deadPortal);
            sendClosePortal(portalName);
            deadPortal.clear();
        }
    }

    public synchronized void fetch(ResultCursor cursor, ResultHandler handler, int fetchSize)
    throws SQLException {
        waitOnLock();
        ((FetchableResultCursor)cursor).fetch(this, handler, fetchSize);
    }

    void fetchFromPortal(Portal portal, ResultHandler handler, int fetchSize) {
        try
        {
            processDeadParsedQueries();
            processDeadPortals();

            QueryRunner queryRunner = new QueryRunner(this, protoConnection, pgStream, logger, allowEncodingChanges, 0);
            queryRunner.sendExecute(portal.getQuery(), portal, fetchSize);
            protocolHelper.sendSync();

            queryRunner.processResults(handler, 0);
        }
        catch (IOException e)
        {
            protoConnection.close();
            handler.handleError(new PSQLException(GT.tr("An I/O error occurred while sending to the backend."), PSQLState.CONNECTION_FAILURE, e));
        }
    }

    private long nextUniqueID = 1;
    private final ProtocolConnectionImpl protoConnection;
    private final PGStream pgStream;
    private final ProtocolHelper protocolHelper;
    private final Logger logger;
    private final boolean allowEncodingChanges;

    /**
     * The number of queries executed so far without processing any results.
     * Used to avoid deadlocks, see MAX_BUFFERED_QUERIES.
     */
    private int queryCount;

    private final SimpleQuery beginTransactionQuery = new SimpleQuery(new String[] { "BEGIN" }, null);

    private final SimpleQuery EMPTY_QUERY = new SimpleQuery(new String[] { "" }, null);
}
