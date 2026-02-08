package com.example.tassmud.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * Central database connection provider with transaction support.
 *
 * <p>Normal mode: each call to {@link #getConnection()} returns a fresh auto-commit connection
 * (same behavior as the old per-method {@code DriverManager.getConnection()} pattern).
 *
 * <p>Transaction mode: {@link #runInTransaction(Supplier)} sets a thread-local connection with
 * auto-commit disabled. All DAO methods called from that thread will share the same connection.
 * On success, the transaction commits; on failure, it rolls back.
 *
 * <p>DAO methods must use {@link #getConnection()} instead of {@code DriverManager.getConnection()}.
 * When inside a transaction, the returned connection is a non-closing proxy wrapper
 * so that try-with-resources in individual DAO methods won't accidentally close the shared connection.
 */
public final class TransactionManager {

    private static final Logger logger = LoggerFactory.getLogger(TransactionManager.class);

    static final String URL = System.getProperty("tassmud.db.url",
            "jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1");
    static final String USER = "sa";
    static final String PASS = "";

    private static final ThreadLocal<Connection> TX_CONNECTION = new ThreadLocal<>();

    private TransactionManager() {} // utility class

    /**
     * Get a database connection. If a transaction is active on this thread,
     * returns a non-closing wrapper around the shared transaction connection.
     * Otherwise, returns a fresh auto-commit connection.
     */
    public static Connection getConnection() throws SQLException {
        Connection txConn = TX_CONNECTION.get();
        if (txConn != null) {
            return wrapNonClosing(txConn);
        }
        return DriverManager.getConnection(URL, USER, PASS);
    }

    /**
     * Wrap a connection so that {@code close()} is a no-op.
     * This prevents try-with-resources in DAO methods from closing
     * the shared transaction connection.
     */
    private static Connection wrapNonClosing(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("close".equals(name)) {
                        return null; // no-op — TransactionManager owns the lifecycle
                    }
                    if ("isClosed".equals(name)) {
                        return delegate.isClosed();
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /**
     * Execute a block of code within a single database transaction.
     * All DAO calls within the supplier will share the same connection.
     *
     * @param action the code to run transactionally
     * @param <T>    the return type
     * @return the result of the action
     * @throws RuntimeException wrapping any SQLException on commit/rollback failure
     */
    public static <T> T runInTransaction(Supplier<T> action) {
        if (TX_CONNECTION.get() != null) {
            // Already in a transaction — just run the action (nested call)
            return action.get();
        }

        Connection conn = null;
        try {
            conn = DriverManager.getConnection(URL, USER, PASS);
            conn.setAutoCommit(false);
            TX_CONNECTION.set(conn);

            T result = action.get();

            conn.commit();
            return result;
        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.rollback();
                    logger.debug("[tx] Transaction rolled back: {}", e.getMessage());
                } catch (SQLException rollbackEx) {
                    logger.warn("[tx] Rollback failed: {}", rollbackEx.getMessage());
                }
            }
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new RuntimeException("Transaction failed", e);
        } finally {
            TX_CONNECTION.remove();
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                    conn.close();
                } catch (SQLException closeEx) {
                    logger.warn("[tx] Connection close failed: {}", closeEx.getMessage());
                }
            }
        }
    }

    /**
     * Void variant of {@link #runInTransaction(Supplier)}.
     */
    public static void runInTransaction(Runnable action) {
        runInTransaction(() -> {
            action.run();
            return null;
        });
    }

    /**
     * Check if the current thread is inside a transaction.
     */
    public static boolean isInTransaction() {
        return TX_CONNECTION.get() != null;
    }
}
