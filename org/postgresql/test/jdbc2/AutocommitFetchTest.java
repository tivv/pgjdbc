package org.postgresql.test.jdbc2;

/**
 * Created by tivv on 10/4/14.
 */
public class AutocommitFetchTest extends CursorFetchTest{
    public AutocommitFetchTest(String name) {
        super(name);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        con.setAutoCommit(true);
    }
}
