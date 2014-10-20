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
import org.postgresql.test.util.TmpFileInputStream;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.io.*;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class QueryRunner implements FetchableResultCursor {
    private final QueryExecutorImpl queryExecutor;
    private final ProtocolConnectionImpl protoConnection;
    private final Logger logger;
    private final boolean allowEncodingChanges;
    private final int flags;
    private PGStream pgStream;

    private final ArrayList pendingParseQueue = new ArrayList(); // list of SimpleQuery instances
    private final ArrayList pendingBindQueue = new ArrayList(); // list of Portal instances
    private final ArrayList pendingExecuteQueue = new ArrayList(); // list of {SimpleQuery,Portal} object arrays
    private final ArrayList pendingDescribeStatementQueue = new ArrayList(); // list of {SimpleQuery, SimpleParameterList, Boolean} object arrays
    private final ArrayList pendingDescribePortalQueue = new ArrayList(); // list of SimpleQuery
    private InputStream swappedData;

    int parseIndex = 0;
    int describeIndex = 0;
    int describePortalIndex = 0;
    int bindIndex = 0;
    int executeIndex = 0;

    public QueryRunner(QueryExecutorImpl queryExecutor, ProtocolConnectionImpl protoConnection, PGStream pgStream,
                       Logger logger, boolean allowEncodingChanges, int flags) {
        this.queryExecutor = queryExecutor;
        this.protoConnection = protoConnection;
        this.pgStream = pgStream;
        this.logger = logger;
        this.allowEncodingChanges = allowEncodingChanges;
        this.flags = flags;
    }

    void sendParse(SimpleQuery query, SimpleParameterList params, boolean oneShot) throws IOException {
        // Already parsed, or we have a Parse pending and the types are right?
        int[] typeOIDs = params.getTypeOIDs();
        if (query.isPreparedFor(typeOIDs))
            return;

        // Clean up any existing statement, as we can't use it.
        query.unprepare();
        queryExecutor.processDeadParsedQueries();

        // Remove any cached Field values
        query.setFields(null);

        String statementName = null;
        if (!oneShot)
        {
            // Generate a statement name to use.
            statementName = queryExecutor.allocateStatementName();

            // And prepare the new statement.
            // NB: Must clone the OID array, as it's a direct reference to
            // the SimpleParameterList's internal array that might be modified
            // under us.
            query.setStatementName(statementName);
            query.setStatementTypes((int[])typeOIDs.clone());
        }

        byte[] encodedStatementName = query.getEncodedStatementName();
        String[] fragments = query.getFragments();

        if (logger.logDebug())
        {
            StringBuffer sbuf = new StringBuffer(" FE=> Parse(stmt=" + statementName + ",query=\"");
            for (int i = 0; i < fragments.length; ++i)
            {
                if (i > 0)
                    sbuf.append("$").append(i);
                sbuf.append(fragments[i]);
            }
            sbuf.append("\",oids={");
            for (int i = 1; i <= params.getParameterCount(); ++i)
            {
                if (i != 1)
                    sbuf.append(",");
                sbuf.append(params.getTypeOID(i));
            }
            sbuf.append("})");
            logger.debug(sbuf.toString());
        }

        //
        // Send Parse.
        //

        byte[][] parts = new byte[fragments.length * 2 - 1][];
        int j = 0;
        int encodedSize = 0;

        // Total size = 4 (size field)
        //            + N + 1 (statement name, zero-terminated)
        //            + N + 1 (query, zero terminated)
        //            + 2 (parameter count) + N * 4 (parameter types)
        // original query: "frag0 ? frag1 ? frag2"
        // fragments: { "frag0", "frag1", "frag2" }
        // output: "frag0 $1 frag1 $2 frag2"
        for (int i = 0; i < fragments.length; ++i)
        {
            if (i != 0)
            {
                parts[j] = Utils.encodeUTF8("$" + i);
                encodedSize += parts[j].length;
                ++j;
            }

            parts[j] = Utils.encodeUTF8(fragments[i]);
            encodedSize += parts[j].length;
            ++j;
        }

        encodedSize = 4
                      + (encodedStatementName == null ? 0 : encodedStatementName.length) + 1
                      + encodedSize + 1
                      + 2 + 4 * params.getParameterCount();

        pgStream.SendChar('P'); // Parse
        pgStream.SendInteger4(encodedSize);
        if (encodedStatementName != null)
            pgStream.Send(encodedStatementName);
        pgStream.SendChar(0);   // End of statement name
        for (int i = 0; i < parts.length; ++i)
        { // Query string
            pgStream.Send(parts[i]);
        }
        pgStream.SendChar(0);       // End of query string.
        pgStream.SendInteger2(params.getParameterCount());       // # of parameter types specified
        for (int i = 1; i <= params.getParameterCount(); ++i)
            pgStream.SendInteger4(params.getTypeOID(i));

        pendingParseQueue.add(new Object[]{query, query.getStatementName()});
    }

    void sendBind(SimpleQuery query, SimpleParameterList params,
                  Portal portal, boolean noBinaryTransfer) throws IOException {
        //
        // Send Bind.
        //

        String statementName = query.getStatementName();
        byte[] encodedStatementName = query.getEncodedStatementName();
        byte[] encodedPortalName = (portal == null ? null : portal.getEncodedPortalName());

        if (logger.logDebug())
        {
            StringBuffer sbuf = new StringBuffer(" FE=> Bind(stmt=" + statementName + ",portal=" + portal);
            for (int i = 1; i <= params.getParameterCount(); ++i)
            {
                sbuf.append(",$").append(i).append("=<").append(params.toString(i)).append(">");
            }
            sbuf.append(")");
            logger.debug(sbuf.toString());
        }

        // Total size = 4 (size field) + N + 1 (destination portal)
        //            + N + 1 (statement name)
        //            + 2 (param format code count) + N * 2 (format codes)
        //            + 2 (param value count) + N (encoded param value size)
        //            + 2 (result format code count, 0)
        long encodedSize = 0;
        for (int i = 1; i <= params.getParameterCount(); ++i)
        {
            if (params.isNull(i))
                encodedSize += 4;
            else
                encodedSize += (long)4 + params.getV3Length(i);
        }

        // This is not the number of binary fields, but the total number
        // of fields if any of them are binary or zero if all of them
        // are text.

        int numBinaryFields = 0;
        Field[] fields = query.getFields();
        if (!noBinaryTransfer && fields != null) {
            for (int i = 0; i < fields.length; ++i) {
                if (useBinary(fields[i])) {
                    fields[i].setFormat(Field.BINARY_FORMAT);
                    numBinaryFields = fields.length;
                }
            }
        }

        encodedSize = 4
                      + (encodedPortalName == null ? 0 : encodedPortalName.length) + 1
                      + (encodedStatementName == null ? 0 : encodedStatementName.length) + 1
                      + 2 + params.getParameterCount() * 2
                      + 2 + encodedSize
                      + 2 + numBinaryFields * 2;

        // backend's MaxAllocSize is the largest message that can
        // be received from a client.  If we have a bigger value
        // from either very large parameters or incorrent length
        // descriptions of setXXXStream we do not send the bind
        // messsage.
        //
        if (encodedSize > 0x3fffffff)
        {
            throw new PGBindException(new IOException(GT.tr("Bind message length {0} too long.  This can be caused by very large or incorrect length specifications on InputStream parameters.", new Long(encodedSize))));
        }

        pgStream.SendChar('B');                  // Bind
        pgStream.SendInteger4((int)encodedSize);      // Message size
        if (encodedPortalName != null)
            pgStream.Send(encodedPortalName);    // Destination portal name.
        pgStream.SendChar(0);                    // End of portal name.
        if (encodedStatementName != null)
            pgStream.Send(encodedStatementName); // Source statement name.
        pgStream.SendChar(0);                    // End of statement name.

        pgStream.SendInteger2(params.getParameterCount());      // # of parameter format codes
        for (int i = 1; i <= params.getParameterCount(); ++i)
            pgStream.SendInteger2(params.isBinary(i) ? 1 : 0);  // Parameter format code

        pgStream.SendInteger2(params.getParameterCount());      // # of parameter values

        // If an error occurs when reading a stream we have to
        // continue pumping out data to match the length we
        // said we would.  Once we've done that we throw
        // this exception.  Multiple exceptions can occur and
        // it really doesn't matter which one is reported back
        // to the caller.
        //
        PGBindException bindException = null;

        for (int i = 1; i <= params.getParameterCount(); ++i)
        {
            if (params.isNull(i))
                pgStream.SendInteger4( -1);                      // Magic size of -1 means NULL
            else
            {
                pgStream.SendInteger4(params.getV3Length(i));   // Parameter size
                try
                {
                    params.writeV3Value(i, pgStream);                 // Parameter value
                }
                catch (PGBindException be)
                {
                    bindException = be;
                }
            }
        }

        pgStream.SendInteger2(numBinaryFields);   // # of result format codes
        for (int i = 0; i < numBinaryFields; ++i) {
            pgStream.SendInteger2(fields[i].getFormat());
        }

        pendingBindQueue.add(portal);

        if (bindException != null)
        {
            throw bindException;
        }
    }

    /**
     * Returns true if the specified field should be retrieved using binary
     * encoding.
     *
     * @param field The field whose Oid type to analyse.
     * @return True if {@link Field#BINARY_FORMAT} should be used, false if
     * {@link Field#BINARY_FORMAT}.
     */
    private boolean useBinary(Field field) {
        int oid = field.getOID();
        return protoConnection.useBinaryForReceive(oid);
    }

    void sendDescribePortal(SimpleQuery query, Portal portal) throws IOException {
        //
        // Send Describe.
        //

        if (logger.logDebug())
        {
            logger.debug(" FE=> Describe(portal=" + portal + ")");
        }

        byte[] encodedPortalName = (portal == null ? null : portal.getEncodedPortalName());

        // Total size = 4 (size field) + 1 (describe type, 'P') + N + 1 (portal name)
        int encodedSize = 4 + 1 + (encodedPortalName == null ? 0 : encodedPortalName.length) + 1;

        pgStream.SendChar('D');               // Describe
        pgStream.SendInteger4(encodedSize); // message size
        pgStream.SendChar('P');               // Describe (Portal)
        if (encodedPortalName != null)
            pgStream.Send(encodedPortalName); // portal name to close
        pgStream.SendChar(0);                 // end of portal name

        pendingDescribePortalQueue.add(query);
        query.setPortalDescribed(true);
    }

    void sendDescribeStatement(SimpleQuery query, SimpleParameterList params, boolean describeOnly) throws IOException {
        // Send Statement Describe

        if (logger.logDebug())
        {
            logger.debug(" FE=> Describe(statement=" + query.getStatementName()+")");
        }

        byte[] encodedStatementName = query.getEncodedStatementName();

        // Total size = 4 (size field) + 1 (describe type, 'S') + N + 1 (portal name)
        int encodedSize = 4 + 1 + (encodedStatementName == null ? 0 : encodedStatementName.length) + 1;

        pgStream.SendChar('D');                     // Describe
        pgStream.SendInteger4(encodedSize);         // Message size
        pgStream.SendChar('S');                     // Describe (Statement);
        if (encodedStatementName != null)
            pgStream.Send(encodedStatementName);    // Statement name
        pgStream.SendChar(0);                       // end message

        pendingDescribeStatementQueue.add(new Object[]{query, params, new Boolean(describeOnly), query.getStatementName()});
        pendingDescribePortalQueue.add(query);
        query.setStatementDescribed(true);
        query.setPortalDescribed(true);
    }

    void sendExecute(SimpleQuery query, Portal portal, int limit) throws IOException {
        //
        // Send Execute.
        //

        if (logger.logDebug())
        {
            logger.debug(" FE=> Execute(portal=" + portal + ",limit=" + limit + ")");
        }

        byte[] encodedPortalName = (portal == null ? null : portal.getEncodedPortalName());
        int encodedSize = (encodedPortalName == null ? 0 : encodedPortalName.length);

        // Total size = 4 (size field) + 1 + N (source portal) + 4 (max rows)
        pgStream.SendChar('E');              // Execute
        pgStream.SendInteger4(4 + 1 + encodedSize + 4);  // message size
        if (encodedPortalName != null)
            pgStream.Send(encodedPortalName); // portal name
        pgStream.SendChar(0);                 // portal name terminator
        pgStream.SendInteger4(limit);       // row limit

        pendingExecuteQueue.add(new Object[] { query, portal });
    }

    protected void processResults(ResultHandler handler, int swapLimit) throws IOException {
        boolean noResults = (flags & QueryExecutor.QUERY_NO_RESULTS) != 0;
        boolean bothRowsAndStatus = (flags & QueryExecutor.QUERY_BOTH_ROWS_AND_STATUS) != 0;

        List tuples = null;

        int len;
        int c;
        boolean endQuery = false;

        // At the end of a command execution we have the CommandComplete
        // message to tell us we're done, but with a describeOnly command
        // we have no real flag to let us know we're done.  We've got to
        // look for the next RowDescription or NoData message and return
        // from there.
        boolean doneAfterRowDescNoData = false;

        while (!endQuery)
        {
            c = pgStream.ReceiveChar();
            switch (c)
            {
            case 'A':  // Asynchronous Notify
                queryExecutor.receiveAsyncNotify();
                break;

            case '1':    // Parse Complete (response to Parse)
                pgStream.ReceiveInteger4(); // len, discarded

                Object[] parsedQueryAndStatement = (Object[])pendingParseQueue.get(parseIndex++);

                SimpleQuery parsedQuery = (SimpleQuery)parsedQueryAndStatement[0];
                String parsedStatementName = (String)parsedQueryAndStatement[1];

                if (logger.logDebug())
                    logger.debug(" <=BE ParseComplete [" + parsedStatementName + "]");

                queryExecutor.registerParsedQuery(parsedQuery, parsedStatementName);
                break;

            case 't':    // ParameterDescription
                pgStream.ReceiveInteger4(); // len, discarded

                if (logger.logDebug())
                    logger.debug(" <=BE ParameterDescription");

                {
                    Object describeData[] = (Object[])pendingDescribeStatementQueue.get(describeIndex);
                    SimpleQuery query = (SimpleQuery)describeData[0];
                    SimpleParameterList params = (SimpleParameterList)describeData[1];
                    boolean describeOnly = ((Boolean)describeData[2]).booleanValue();
                    String origStatementName = (String)describeData[3];

                    int numParams = pgStream.ReceiveInteger2();

                    for (int i=1; i<=numParams; i++) {
                        int typeOid = pgStream.ReceiveInteger4();
                        params.setResolvedType(i, typeOid);
                    }

                    // Since we can issue multiple Parse and DescribeStatement
                    // messages in a single network trip, we need to make
                    // sure the describe results we requested are still
                    // applicable to the latest parsed query.
                    //
                    if ((origStatementName == null && query.getStatementName() == null) || (origStatementName != null && origStatementName.equals(query.getStatementName()))) {
                        query.setStatementTypes((int[])params.getTypeOIDs().clone());
                    }

                    if (describeOnly)
                        doneAfterRowDescNoData = true;
                    else
                        describeIndex++;
                }
                break;

            case '2':    // Bind Complete  (response to Bind)
                pgStream.ReceiveInteger4(); // len, discarded

                Portal boundPortal = (Portal)pendingBindQueue.get(bindIndex++);
                if (logger.logDebug())
                    logger.debug(" <=BE BindComplete [" + boundPortal + "]");

                queryExecutor.registerOpenPortal(boundPortal);
                break;

            case '3':    // Close Complete (response to Close)
                pgStream.ReceiveInteger4(); // len, discarded
                if (logger.logDebug())
                    logger.debug(" <=BE CloseComplete");
                break;

            case 'n':    // No Data        (response to Describe)
                pgStream.ReceiveInteger4(); // len, discarded
                if (logger.logDebug())
                    logger.debug(" <=BE NoData");

                describePortalIndex++;

                if (doneAfterRowDescNoData) {
                    Object describeData[] = (Object[])pendingDescribeStatementQueue.get(describeIndex++);
                    SimpleQuery currentQuery = (SimpleQuery)describeData[0];

                    Field[] fields = currentQuery.getFields();

                    if (fields != null)
                    { // There was a resultset.
                        tuples = new ArrayList();
                        handler.handleResultRows(currentQuery, fields, tuples, null);
                        tuples = null;
                    }
                }
                break;

            case 's':    // Portal Suspended (end of Execute)
                // nb: this appears *instead* of CommandStatus.
                // Must be a SELECT if we suspended, so don't worry about it.

                pgStream.ReceiveInteger4(); // len, discarded
                if (logger.logDebug())
                    logger.debug(" <=BE PortalSuspended");

                {
                    Object[] executeData = (Object[])pendingExecuteQueue.get(executeIndex++);
                    SimpleQuery currentQuery = (SimpleQuery)executeData[0];
                    Portal currentPortal = (Portal)executeData[1];

                    Field[] fields = currentQuery.getFields();
                    if (fields != null && !noResults && tuples == null)
                        tuples = new ArrayList();

                    handler.handleResultRows(currentQuery, fields, tuples, currentPortal);
                }

                tuples = null;
                break;

            case 'C':  // Command Status (end of Execute)
                // Handle status.
                String status = queryExecutor.receiveCommandStatus();

                doneAfterRowDescNoData = false;

                {
                    Object[] executeData = (Object[])pendingExecuteQueue.get(executeIndex++);
                    SimpleQuery currentQuery = (SimpleQuery)executeData[0];
                    Portal currentPortal = (Portal)executeData[1];

                    Field[] fields = currentQuery.getFields();
                    if (fields != null && !noResults && tuples == null)
                        tuples = new ArrayList();

                    if (fields != null || tuples != null)
                    { // There was a resultset.
                        handler.handleResultRows(currentQuery, fields, tuples, null);
                        tuples = null;

                        if (bothRowsAndStatus)
                            interpretCommandStatus(status, handler);
                    }
                    else
                    {
                        interpretCommandStatus(status, handler);
                    }

                    if (currentPortal != null)
                        currentPortal.close();
                }
                break;

            case 'D':  // Data Transfer (ongoing Execute response)
                byte[][] tuple = null;
                try {
                    tuple = pgStream.ReceiveTupleV3();
                } catch(OutOfMemoryError oome) {
                    if (!noResults) {
                        handler.handleError(new PSQLException(GT.tr("Ran out of memory retrieving query results."), PSQLState.OUT_OF_MEMORY, oome));
                    }
                }


                if (!noResults)
                {
                    if (tuples == null)
                        tuples = new ArrayList();
                    tuples.add(tuple);
                }

                if (logger.logDebug()) {
                    int length;
                    if (tuple == null) {
                        length = -1;
                    } else {
                        length = 0;
                        for (int i=0; i< tuple.length; ++i) {
                            if (tuple[i] == null) continue;
                            length += tuple[i].length;
                        }
                    }
                    logger.debug(" <=BE DataRow(len=" + length + ")");
                }

                if (swapLimit > 0 && tuples.size() > swapLimit) {
                    if (swappedData == null)
                    {
                        if (logger.logDebug())
                            logger.debug("Already received " + tuples.size() + " tuples that is over " + swapLimit +
                            " limit. Swapping remained to tmp file");
                        swappedData = swapToFile(handler);
                        pgStream = new PGStream(pgStream.getHostSpec(), swappedData, pgStream.getEncoding());
                    }

                    Object[] executeData = (Object[])pendingExecuteQueue.get(executeIndex);
                    SimpleQuery currentQuery = (SimpleQuery)executeData[0];

                    handler.handleResultRows(currentQuery, currentQuery.getFields(), tuples, this);
                    tuples = null;
                    endQuery = true;
                }

                break;

            case 'E':  // Error Response (response to pretty much everything; backend then skips until Sync)
                SQLException error = queryExecutor.receiveErrorResponse();
                handler.handleError(error);

                // keep processing
                break;

            case 'I':  // Empty Query (end of Execute)
                pgStream.ReceiveInteger4();

                if (logger.logDebug())
                    logger.debug(" <=BE EmptyQuery");

                {
                    Object[] executeData = (Object[])pendingExecuteQueue.get(executeIndex++);
                    Query currentQuery = (Query)executeData[0];
                    Portal currentPortal = (Portal)executeData[1];
                    handler.handleCommandStatus("EMPTY", 0, 0);
                    if (currentPortal != null)
                        currentPortal.close();
                }

                break;

            case 'N':  // Notice Response
                SQLWarning warning = queryExecutor.receiveNoticeResponse();
                handler.handleWarning(warning);
                break;

            case 'S':    // Parameter Status
                endQuery = processParameterStatus(handler, endQuery);
                break;

            case 'T':  // Row Description (response to Describe)
                Field[] fields = queryExecutor.receiveFields();
                tuples = new ArrayList();

                SimpleQuery query = (SimpleQuery)pendingDescribePortalQueue.get(describePortalIndex++);
                query.setFields(fields);

                if (doneAfterRowDescNoData) {
                    Object describeData[] = (Object[])pendingDescribeStatementQueue.get(describeIndex++);
                    SimpleQuery currentQuery = (SimpleQuery)describeData[0];
                    currentQuery.setFields(fields);

                    handler.handleResultRows(currentQuery, fields, tuples, null);
                    tuples = null;
                }
                break;

            case 'Z':    // Ready For Query (eventual response to Sync)
                queryExecutor.receiveRFQ();
                endQuery = true;

                // Reset the statement name of Parses that failed.
                while (parseIndex < pendingParseQueue.size())
                {
                    Object[] failedQueryAndStatement = (Object[])pendingParseQueue.get(parseIndex++);
                    SimpleQuery failedQuery = (SimpleQuery)failedQueryAndStatement[0];
                    failedQuery.unprepare();
                }

                pendingParseQueue.clear();              // No more ParseComplete messages expected.
                pendingDescribeStatementQueue.clear();  // No more ParameterDescription messages expected.
                pendingDescribePortalQueue.clear();     // No more RowDescription messages expected.
                pendingBindQueue.clear();               // No more BindComplete messages expected.
                pendingExecuteQueue.clear();            // No more query executions expected.
                break;

            case 'G':  // CopyInResponse
                processCopyInResponse();
                break;

            case 'H':  // CopyOutResponse
                if (logger.logDebug())
                    logger.debug(" <=BE CopyOutResponse");
 
                queryExecutor.skipMessage();
                // In case of CopyOutResponse, we cannot abort data transfer,
                // so just throw an error and ignore CopyData messages
                handler.handleError(new PSQLException(GT.tr("The driver currently does not support COPY operations."), PSQLState.NOT_IMPLEMENTED));
                break;

            case 'c':  // CopyDone
                queryExecutor.skipMessage();
                if (logger.logDebug()) {
                    logger.debug(" <=BE CopyDone");
                }
                break;

            case 'd':  // CopyData
                queryExecutor.skipMessage();
                if (logger.logDebug()) {
                    logger.debug(" <=BE CopyData");
                }
                break;

            default:
                throw new IOException("Unexpected packet type: " + c);
            }

        }
    }

    private boolean processParameterStatus(ResultHandler handler, boolean endQuery) throws IOException {
        int l_len = pgStream.ReceiveInteger4();
        String name = pgStream.ReceiveString();
        String value = pgStream.ReceiveString();
        if (logger.logDebug())
            logger.debug(" <=BE ParameterStatus(" + name + " = " + value + ")");

        if (name.equals("client_encoding") && !value.equalsIgnoreCase("UTF8") && !allowEncodingChanges)
        {
            protoConnection.close(); // we're screwed now; we can't trust any subsequent string.
            handler.handleError(new PSQLException(GT.tr("The server''s client_encoding parameter was changed to {0}. The JDBC driver requires client_encoding to be UTF8 for correct operation.", value), PSQLState.CONNECTION_FAILURE));
            endQuery = true;
        }

        if (name.equals("DateStyle") && !value.startsWith("ISO,"))
        {
            protoConnection.close(); // we're screwed now; we can't trust any subsequent date.
            handler.handleError(new PSQLException(GT.tr("The server''s DateStyle parameter was changed to {0}. The JDBC driver requires DateStyle to begin with ISO for correct operation.", value), PSQLState.CONNECTION_FAILURE));
            endQuery = true;
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
                handler.handleError(new PSQLException(GT.tr("The server''s standard_conforming_strings parameter was reported as {0}. The JDBC driver expected on or off.", value), PSQLState.CONNECTION_FAILURE));
                endQuery = true;
            }
        }
        return endQuery;
    }

    private void processCopyInResponse() throws IOException {
        if (logger.logDebug()) {
            logger.debug(" <=BE CopyInResponse");
            logger.debug(" FE=> CopyFail");
        }

        // COPY sub-protocol is not implemented yet
        // We'll send a CopyFail message for COPY FROM STDIN so that
        // server does not wait for the data.

        byte[] buf = Utils.encodeUTF8("The JDBC driver currently does not support COPY operations.");
        pgStream.SendChar('f');
        pgStream.SendInteger4(buf.length + 4 + 1);
        pgStream.Send(buf);
        pgStream.SendChar(0);
        pgStream.flush();
        queryExecutor.sendSync();     // send sync message
        queryExecutor.skipMessage();  // skip the response message
    }

    private InputStream swapToFile(ResultHandler handler) throws IOException {
        File tmpFile = File.createTempFile("pgjdbc", ".bin");
        OutputStream out = new BufferedOutputStream(new FileOutputStream(tmpFile));
        try
        {
            boolean endQuery = false;
            while (!endQuery)
            {
                int c = pgStream.ReceiveChar();
                switch (c)
                {
                    case 'A':  // Asynchronous Notify
                        queryExecutor.receiveAsyncNotify();
                        break;
                    case 'Z':    // Ready For Query (eventual response to Sync)
                        endQuery = true;
                    case '1':    // Parse Complete (response to Parse)
                    case 't':    // ParameterDescription
                    case '2':    // Bind Complete  (response to Bind)
                    case '3':    // Close Complete (response to Close)
                    case 'n':    // No Data        (response to Describe)
                    case 's':    // Portal Suspended (end of Execute)
                    case 'C':  // Command Status (end of Execute)
                    case 'D':  // Data Transfer (ongoing Execute response)
                    case 'E':  // Error Response (response to pretty much everything; backend then skips until Sync)
                    case 'I':  // Empty Query (end of Execute)
                    case 'N':  // Notice Response
                    case 'T':  // Row Description (response to Describe)
                    case 'H':  // CopyOutResponse
                        if (logger.logDebug())
                            logger.debug(" <=BE " + ((char) c));
                        out.write(c);
                        int l_len = pgStream.ReceiveAndCopyInteger4(out);
                        // copy l_len-4 (length includes the 4 bytes for message length itself
                        pgStream.CopyTo(out, l_len - 4);

                        break;

                    case 'S':    // Parameter Status
                        endQuery = processParameterStatus(handler, endQuery);
                        break;

                    case 'G':  // CopyInResponse
                        processCopyInResponse();
                        break;

                    case 'c':  // CopyDone
                    case 'd':  // CopyData
                        queryExecutor.skipMessage();
                        if (logger.logDebug())
                            logger.debug(" <=BE " + ((char) c));
                        break;

                    default:
                        throw new IOException("Unexpected packet type: " + c);
                }
            }
            out.close();
        } catch (IOException e) {
            try {
                out.close();
                tmpFile.delete();
            } finally
            {
                throw e;
            }
        }
        if (logger.logDebug())
            logger.debug("Swapped " + tmpFile.length() + " bytes to " + tmpFile);
        return new TmpFileInputStream(tmpFile);
    }

    private void interpretCommandStatus(String status, ResultHandler handler) {
        int update_count = 0;
        long insert_oid = 0;

        if (status.startsWith("INSERT") || status.startsWith("UPDATE") || status.startsWith("DELETE") || status.startsWith("MOVE"))
        {
            try
            {
                long updates = Long.parseLong(status.substring(1 + status.lastIndexOf(' ')));

                // deal with situations where the update modifies more than 2^32 rows
                if ( updates > Integer.MAX_VALUE )
                    update_count = Statement.SUCCESS_NO_INFO;
                else
                    update_count = (int)updates;

                if (status.startsWith("INSERT"))
                    insert_oid = Long.parseLong(status.substring(1 + status.indexOf(' '),
                                                status.lastIndexOf(' ')));
            }
            catch (NumberFormatException nfe)
            {
                handler.handleError(new PSQLException(GT.tr("Unable to interpret the update count in command completion tag: {0}.", status), PSQLState.CONNECTION_FAILURE));
                return ;
            }
        }

        handler.handleCommandStatus(status, update_count, insert_oid);
    }

    @Override
    public void fetch(QueryExecutor queryExecutor, ResultHandler handler, int fetchSize) throws SQLException {
        try
        {
            processResults(handler, fetchSize);
        }
        catch (IOException e)
        {
            protoConnection.close();
            handler.handleError(new PSQLException(GT.tr("An I/O error occurred while sending to the backend."), PSQLState.CONNECTION_FAILURE, e));
        }
    }

    @Override
    public void close() {
        if (swappedData != null) {
            try
            {
                swappedData.close();
            } catch (IOException e)
            {
                if (logger.logInfo())
                {
                    logger.log("Error closing swap stream", e);
                }
            }
        }
    }

    public QueryRunner getRunnerForNextQuery() {
        return new QueryRunner(queryExecutor, protoConnection, pgStream, logger, allowEncodingChanges, flags);
    }
}
