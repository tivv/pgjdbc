/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2014, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core.v3;

import org.postgresql.core.Field;
import org.postgresql.core.Logger;
import org.postgresql.core.PGStream;
import org.postgresql.core.ProtocolConnection;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLWarning;
import org.postgresql.util.ServerErrorMessage;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLWarning;

/**
 * This class implements certain high level V3 protocol primitives to be send and/or received through PGStream.
 * Note that since there are can be multiple ProtocolHelpers for single connection in case of replay scenrios,
 * it can't hold any connection state.
 */
public class ProtocolHelper {
    private final Logger logger;
    private final PGStream pgStream;
    private final ProtocolConnectionImpl protoConnection;

    /**
     * Creates a helper
     * @param logger logger to log messages
     * @param pgStream stream to use for  communications. Can be different to protoConnection one in replay scenarios
     * @param protoConnection connection to send errors/warnings/notifications to
     */
    public ProtocolHelper(Logger logger, PGStream pgStream, ProtocolConnectionImpl protoConnection) {
        this.logger = logger;
        this.pgStream = pgStream;
        this.protoConnection = protoConnection;
    }

    /**
     * Ignore the response message by reading the message length and skipping
     * over those bytes in the communication stream.
     */
    void skipMessage() throws IOException {
        int l_len = pgStream.ReceiveInteger4();
        // skip l_len-4 (length includes the 4 bytes for message length itself
        pgStream.Skip(l_len - 4);
    }

    public void sendSync() throws IOException {
        if (logger.logDebug())
            logger.debug(" FE=> Sync");

        pgStream.SendChar('S');     // Sync
        pgStream.SendInteger4(4); // Length
        pgStream.flush();
    }

    /*
     * Receive the field descriptions from the back end.
     */
    Field[] receiveFields() throws IOException
    {
        int l_msgSize = pgStream.ReceiveInteger4();
        int size = pgStream.ReceiveInteger2();
        Field[] fields = new Field[size];

        if (logger.logDebug())
            logger.debug(" <=BE RowDescription(" + size + ")");

        for (int i = 0; i < fields.length; i++)
        {
            String columnLabel = pgStream.ReceiveString();
            int tableOid = pgStream.ReceiveInteger4();
            short positionInTable = (short)pgStream.ReceiveInteger2();
            int typeOid = pgStream.ReceiveInteger4();
            int typeLength = pgStream.ReceiveInteger2();
            int typeModifier = pgStream.ReceiveInteger4();
            int formatType = pgStream.ReceiveInteger2();
            fields[i] = new Field(columnLabel,
                    "",  /* name not yet determined */
                    typeOid, typeLength, typeModifier, tableOid, positionInTable);
            fields[i].setFormat(formatType);

            if (logger.logDebug())
                logger.debug("        " + fields[i]);
        }

        return fields;
    }

    void receiveAsyncNotify() throws IOException {
        int msglen = pgStream.ReceiveInteger4();
        int pid = pgStream.ReceiveInteger4();
        String msg = pgStream.ReceiveString();
        String param = pgStream.ReceiveString();
        protoConnection.addNotification(new org.postgresql.core.Notification(msg, pid, param));

        if (logger.logDebug())
            logger.debug(" <=BE AsyncNotify(" + pid + "," + msg + "," + param + ")");
    }

    public SQLException receiveErrorResponse() throws IOException {
        // it's possible to get more than one error message for a query
        // see libpq comments wrt backend closing a connection
        // so, append messages to a string buffer and keep processing
        // check at the bottom to see if we need to throw an exception

        int elen = pgStream.ReceiveInteger4();
        String totalMessage = pgStream.ReceiveString(elen - 4);
        ServerErrorMessage errorMsg = new ServerErrorMessage(totalMessage, logger.getLogLevel());

        if (logger.logDebug())
            logger.debug(" <=BE ErrorMessage(" + errorMsg.toString() + ")");

        return new PSQLException(errorMsg);
    }

    public SQLWarning receiveNoticeResponse() throws IOException {
        int nlen = pgStream.ReceiveInteger4();
        ServerErrorMessage warnMsg = new ServerErrorMessage(pgStream.ReceiveString(nlen - 4), logger.getLogLevel());

        if (logger.logDebug())
            logger.debug(" <=BE NoticeResponse(" + warnMsg.toString() + ")");

        return new PSQLWarning(warnMsg);
    }

    public String receiveCommandStatus() throws IOException {
        //TODO: better handle the msg len
        int l_len = pgStream.ReceiveInteger4();
        //read l_len -5 bytes (-4 for l_len and -1 for trailing \0)
        String status = pgStream.ReceiveString(l_len - 5);
        //now read and discard the trailing \0
        pgStream.Receive(1);

        if (logger.logDebug())
            logger.debug(" <=BE CommandStatus(" + status + ")");

        return status;
    }

    void receiveRFQ() throws IOException {
        if (pgStream.ReceiveInteger4() != 5)
            throw new IOException("unexpected length of ReadyForQuery message");

        char tStatus = (char)pgStream.ReceiveChar();
        if (logger.logDebug())
            logger.debug(" <=BE ReadyForQuery(" + tStatus + ")");

        // Update connection state.
        switch (tStatus)
        {
            case 'I':
                protoConnection.setTransactionState(ProtocolConnection.TRANSACTION_IDLE);
                break;
            case 'T':
                protoConnection.setTransactionState(ProtocolConnection.TRANSACTION_OPEN);
                break;
            case 'E':
                protoConnection.setTransactionState(ProtocolConnection.TRANSACTION_FAILED);
                break;
            default:
                throw new IOException("unexpected transaction state in ReadyForQuery message: " + (int)tStatus);
        }
    }

    public PGStream getPgStream() {
        return pgStream;
    }
}
