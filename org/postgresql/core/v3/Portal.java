/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core.v3;

import java.lang.ref.PhantomReference;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;

import org.postgresql.core.*;

/**
 * V3 ResultCursor implementation in terms of backend Portals.
 * This holds the state of a single Portal. We use a PhantomReference
 * managed by our caller to handle resource cleanup.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
class Portal implements FetchableResultCursor {
    Portal(SimpleQuery query, String portalName) {
        this.query = query;
        this.portalName = portalName;
        this.encodedName = Utils.encodeUTF8(portalName);
    }

    public void close() {
        if (cleanupRef != null)
        {
            cleanupRef.clear();
            cleanupRef.enqueue();
            cleanupRef = null;
        }
    }

    @Override
    public void fetch(QueryExecutor queryExecutor, ResultHandler handler, int fetchSize) throws SQLException {
        // Insert a ResultHandler that turns bare command statuses into empty datasets
        // (if the fetch returns no rows, we see just a CommandStatus..)
        final ResultHandler delegateHandler = handler;
        handler = new ResultHandler() {
            public void handleResultRows(Query fromQuery, Field[] fields, List tuples, ResultCursor cursor) {
                delegateHandler.handleResultRows(fromQuery, fields, tuples, cursor);
            }

            public void handleCommandStatus(String status, int updateCount, long insertOID) {
                handleResultRows(getQuery(), null, new ArrayList(), null);
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

        // Now actually run it.

        QueryExecutorImpl v3QueryExecutor = (QueryExecutorImpl) queryExecutor;
        v3QueryExecutor.fetchFromPortal(this, handler, fetchSize);

        handler.handleCompletion();

    }

    String getPortalName() {
        return portalName;
    }

    byte[] getEncodedPortalName() {
        return encodedName;
    }

    SimpleQuery getQuery() {
        return query;
    }

    void setCleanupRef(PhantomReference cleanupRef) {
        this.cleanupRef = cleanupRef;
    }

    public String toString() {
        return portalName;
    }

    // Holding on to a reference to the generating query has
    // the nice side-effect that while this Portal is referenced,
    // so is the SimpleQuery, so the underlying statement won't
    // be closed while the portal is open (the backend closes
    // all open portals when the statement is closed)

    private final SimpleQuery query;
    private final String portalName;
    private final byte[] encodedName;
    private PhantomReference cleanupRef;
}
