package com.ocptools.cms;

import com.ocptools.common.CmsDataException;
import com.ocptools.common.ExternalTimeoutException;
import com.ocptools.config.ToolsConfig;
import com.ocptools.domain.CmsRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Optional;

@ApplicationScoped
public class CmsRepository {
    static final String FIND_LATEST_SQL = """
            SELECT FIRST 1
                   interaction_date_c,
                   cfs_access_id,
                   external_id,
                   pid,
                   exceid
            FROM nil_trama_da_cmsnil
            WHERE cfs_access_id = ?
            ORDER BY interaction_date_c DESC
            """;

    static final String MARK_FOR_RESEND_SQL = """
            UPDATE nil_trama_da_cmsnil
            SET estado_env = 'P'
            WHERE cfs_access_id = ?
            """;

    @Inject
    DataSource dataSource;

    @Inject
    ToolsConfig config;

    public Optional<CmsRecord> findLatest(String accessId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_LATEST_SQL)) {

            statement.setQueryTimeout(config.timeouts().cmsQuerySeconds());
            statement.setString(1, accessId);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }

                return Optional.of(new CmsRecord(
                        asText(resultSet.getObject("interaction_date_c")),
                        resultSet.getString("cfs_access_id"),
                        resultSet.getString("external_id"),
                        resultSet.getString("pid"),
                        resultSet.getString("exceid")
                ));
            }
        } catch (SQLTimeoutException exception) {
            throw new ExternalTimeoutException("La consulta CMS superó el tiempo máximo", exception);
        } catch (SQLException exception) {
            if (isTimeout(exception)) {
                throw new ExternalTimeoutException("La consulta CMS superó el tiempo máximo", exception);
            }
            throw new CmsDataException("No fue posible consultar CMS", exception);
        }
    }

    public int markForResend(String accessId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(MARK_FOR_RESEND_SQL)) {

            statement.setQueryTimeout(config.timeouts().cmsQuerySeconds());
            statement.setString(1, accessId);
            return statement.executeUpdate();
        } catch (SQLTimeoutException exception) {
            throw new ExternalTimeoutException("La actualización CMS superó el tiempo máximo", exception);
        } catch (SQLException exception) {
            if (isTimeout(exception)) {
                throw new ExternalTimeoutException("La actualización CMS superó el tiempo máximo", exception);
            }
            throw new CmsDataException("No fue posible actualizar CMS", exception);
        }
    }

    private static String asText(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean isTimeout(SQLException exception) {
        SQLException current = exception;
        while (current != null) {
            String state = current.getSQLState();
            String message = current.getMessage();
            if ((state != null && state.startsWith("HYT"))
                    || (message != null && message.toLowerCase().contains("timeout"))) {
                return true;
            }
            current = current.getNextException();
        }
        return false;
    }
}
