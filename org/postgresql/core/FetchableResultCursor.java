/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2014, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core;

import java.sql.SQLException;

public interface FetchableResultCursor extends ResultCursor {
    void fetch(QueryExecutor queryExecutor, ResultHandler handler, int fetchSize) throws SQLException;
}
