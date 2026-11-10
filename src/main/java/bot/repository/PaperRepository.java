package bot.repository;

import bot.model.DocType;
import bot.model.Paper;
import bot.model.Paper.FileType;
import bot.model.Stage;
import bot.model.Project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

public class PaperRepository {
    private static final Logger log = LoggerFactory.getLogger(PaperRepository.class);

    private final Connection connection;
    private ProjectRepository projectRepo;

    public PaperRepository(Connection connection, ProjectRepository projectRepo) {
        this.connection = connection;
        this.projectRepo = projectRepo;
    }

    public Optional<Paper> findById(int docId) {
        String sql = "SELECT * FROM documents WHERE docId=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, docId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            log.error("findById docId={} failed", docId, e);
        }
        return Optional.empty();
    }

    public void save(Paper paper) {
        Optional<Paper> existing = findById(paper.getDocId());
        if (existing.isPresent()) {
            update(paper);
        } else {
            insert(paper);
        }
    }

    public List<Paper> findUnpaid() {
        String sql = "SELECT * FROM documents WHERE isPayed=0 AND isBannedByAcceptor=0 " +
                "AND isBannedByRegistrar=0 AND isBannedByReceiver=0 ORDER BY docId";
        List<Paper> papers = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                papers.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("findUnpaid failed", e);
        }
        return papers;
    }

    public List<Paper> findRecent(int limit) {
        String sql = "SELECT * FROM documents ORDER BY docId DESC LIMIT ?";
        List<Paper> papers = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    papers.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            log.error("findRecent limit={} failed", limit, e);
        }
        return papers;
    }

    public List<Paper> findByUser(long chatId) {
        String sql = "SELECT * FROM documents WHERE user=? ORDER BY docId DESC";
        List<Paper> papers = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, String.valueOf(chatId));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                papers.add(mapRow(rs));
            }
        } catch (SQLException e) {
            log.error("findByUser chatId={} failed", chatId, e);
        }
        return papers;
    }

    public int create(DocType docType) {
        String sql = "INSERT INTO documents (uniqueId, typeOfFile) VALUES (?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, String.valueOf(System.currentTimeMillis()));
            ps.setString(2, docType == DocType.INVOICE ? "счет на оплату" : "акт");
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) return keys.getInt(1);
        } catch (SQLException e) {
            log.error("create docType={} failed", docType, e);
        }
        return -1;
    }

    private void insert(Paper paper) {
        String sql = "INSERT INTO documents (docId, fileId, projectTableName, isPhoto, details, typeOfFile, reason, " +
                "statusNote, stage, user, acceptor, registrar, receiver, acceptors, registrars, receivers, " +
                "userTime, acceptorTime, registrarTime, receiverTime, " +
                "isPayed, needsToBeRegistered, isBannedByAcceptor, isBannedByRegistrar, isBannedByReceiver) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            fillStatement(ps, paper);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("insert docId={} failed", paper.getDocId(), e);
        }
    }

    private void update(Paper paper) {
        String sql = "UPDATE documents SET fileId=?, projectTableName=?, isPhoto=?, details=?, reason=?, statusNote=?, stage=?, " +
                "user=?, acceptor=?, registrar=?, receiver=?, acceptors=?, registrars=?, receivers=?, " +
                "userTime=?, acceptorTime=?, registrarTime=?, receiverTime=?, " +
                "isPayed=?, needsToBeRegistered=?, isBannedByAcceptor=?, isBannedByRegistrar=?, isBannedByReceiver=? " +
                "WHERE docId=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, paper.getFileId());
            ps.setString(2, paper.getProject() != null ? paper.getProject().getTableName() : null);
            ps.setInt(3, paper.getFileType() == FileType.PHOTO ? 1 : 0);
            ps.setString(4, paper.getDetails());
            ps.setString(5, paper.getReason());
            ps.setString(6, paper.getStatusNote());
            ps.setString(7, paper.getStage() != null ? paper.getStage().name() : null);
            ps.setString(8, String.valueOf(paper.getUserId()));
            ps.setString(9, paper.getAcceptorId() == 0 ? null : String.valueOf(paper.getAcceptorId()));
            ps.setString(10, paper.getRegistrarId() == 0 ? null : String.valueOf(paper.getRegistrarId()));
            ps.setString(11, paper.getReceiverId() == 0 ? null : String.valueOf(paper.getReceiverId()));
            ps.setString(12, mapToString(paper.getApprovers()));
            ps.setString(13, mapToString(paper.getRegistrars()));
            ps.setString(14, mapToString(paper.getReceivers()));
            ps.setLong(15, paper.getUserTime());
            ps.setLong(16, paper.getAcceptorTime());
            ps.setLong(17, paper.getRegistrarTime());
            ps.setLong(18, paper.getReceiverTime());

            Stage stage = paper.getStage();
            ps.setInt(19, stage == Stage.PAID ? 1 : 0);
            ps.setInt(20, paper.getNeedsRegistration() ? 1 : 0);
            ps.setInt(21, stage == Stage.BLOCKED_AT_ACCEPTANCE ? 1 : 0);
            ps.setInt(22, stage == Stage.BLOCKED_AT_REGISTRATION ? 1 : 0);
            ps.setInt(23, stage == Stage.BLOCKED_AT_PAYMENT ? 1 : 0);

            ps.setInt(24, paper.getDocId());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("update docId={} failed", paper.getDocId(), e);
        }
    }

    private void fillStatement(PreparedStatement ps, Paper paper) throws SQLException {
        ps.setInt(1, paper.getDocId());
        ps.setString(2, paper.getFileId());
        ps.setString(3, paper.getProject() != null ? paper.getProject().getTableName() : null);
        ps.setInt(4, paper.getFileType() == FileType.PHOTO ? 1 : 0);
        ps.setString(5, paper.getDetails());
        ps.setString(6, paper.getDocType() == DocType.INVOICE ? "счет на оплату" : "акт");
        ps.setString(7, paper.getReason());
        ps.setString(8, paper.getStatusNote());
        ps.setString(9, paper.getStage() != null ? paper.getStage().name() : null);
        ps.setString(10, String.valueOf(paper.getUserId()));
        ps.setString(11, paper.getAcceptorId() == 0 ? null : String.valueOf(paper.getAcceptorId()));
        ps.setString(12, paper.getRegistrarId() == 0 ? null : String.valueOf(paper.getRegistrarId()));
        ps.setString(13, paper.getReceiverId() == 0 ? null : String.valueOf(paper.getReceiverId()));
        ps.setString(14, mapToString(paper.getApprovers()));
        ps.setString(15, mapToString(paper.getRegistrars()));
        ps.setString(16, mapToString(paper.getReceivers()));
        ps.setLong(17, paper.getUserTime());
        ps.setLong(18, paper.getAcceptorTime());
        ps.setLong(19, paper.getRegistrarTime());
        ps.setLong(20, paper.getReceiverTime());

        Stage stage = paper.getStage();
        ps.setInt(21, stage == Stage.PAID ? 1 : 0);
        ps.setInt(22, paper.getNeedsRegistration() ? 1 : 0);
        ps.setInt(23, stage == Stage.BLOCKED_AT_ACCEPTANCE ? 1 : 0);
        ps.setInt(24, stage == Stage.BLOCKED_AT_REGISTRATION ? 1 : 0);
        ps.setInt(25, stage == Stage.BLOCKED_AT_PAYMENT ? 1 : 0);
    }

    private Paper mapRow(ResultSet rs) throws SQLException {
        int docId = rs.getInt("docId");
        String typeOfFile = rs.getString("typeOfFile");
        boolean isPhoto = rs.getInt("isPhoto") == 1;

        DocType docType = "счет на оплату".equals(typeOfFile) ? DocType.INVOICE : DocType.ACT;
        FileType fileType = isPhoto ? FileType.PHOTO : FileType.DOCUMENT;

        String details = rs.getString("details");
        String fileId = rs.getString("fileId");
        String tableName = rs.getString("projectTableName");
        Paper paper;
        if (tableName == null)
            paper = new Paper(docId, docType, fileType, details, null, fileId);
        else
            paper = new Paper(docId, docType, fileType, details, projectRepo.getProject(tableName), fileId);

        paper.setReason(rs.getString("reason"));
        paper.setStatusNote(rs.getString("statusNote"));

        String user = rs.getString("user");
        if (user != null) paper.setUserId(Long.parseLong(user));

        String acceptor = rs.getString("acceptor");
        if (acceptor != null) paper.setAcceptorId(Long.parseLong(acceptor));

        String registrar = rs.getString("registrar");
        if (registrar != null) paper.setRegistrarId(Long.parseLong(registrar));

        String receiver = rs.getString("receiver");
        if (receiver != null) paper.setReceiverId(Long.parseLong(receiver));

        paper.setApprovers(parseMap(rs.getString("acceptors")));
        paper.setRegistrars(parseMap(rs.getString("registrars")));
        paper.setReceivers(parseMap(rs.getString("receivers")));

        paper.setUserTime(rs.getLong("userTime"));
        paper.setAcceptorTime(rs.getLong("acceptorTime"));
        paper.setRegistrarTime(rs.getLong("registrarTime"));
        paper.setReceiverTime(rs.getLong("receiverTime"));

        paper.setNeedsRegistration(rs.getInt("needsToBeRegistered") == 1);

        String stageName = rs.getString("stage");
        if (stageName != null && !stageName.isEmpty()) {
            paper.setStage(Stage.valueOf(stageName));
        } else {
            paper.setStage(resolveStage(rs));
        }

        return paper;
    }

    private Stage resolveStage(ResultSet rs) throws SQLException {
        if (rs.getInt("isBannedByAcceptor") == 1) return Stage.BLOCKED_AT_ACCEPTANCE;
        if (rs.getInt("isBannedByRegistrar") == 1) return Stage.BLOCKED_AT_REGISTRATION;
        if (rs.getInt("isBannedByReceiver") == 1) return Stage.BLOCKED_AT_PAYMENT;
        if (rs.getInt("isPayed") == 1) return Stage.PAID;
        if (rs.getString("receiver") != null) return Stage.PAYING;
        if (rs.getInt("needsToBeRegistered") == 1) {
            if (rs.getString("registrar") != null) return Stage.REGISTERING;
            if (rs.getString("acceptor") != null) return Stage.ACCEPTED_WITH_REG;
        }
        if (rs.getString("acceptor") != null) return Stage.ACCEPTING;
        if (rs.getString("user") != null) return Stage.SUBMITTED;
        return Stage.SUBMITTED;
    }

    private HashMap<Long, Integer> parseMap(String text) {
        HashMap<Long, Integer> map = new HashMap<>();
        if (text == null || text.isEmpty()) return map;
        for (String entry : text.split(",")) {
            String[] parts = entry.split("-");
            if (parts.length == 2) {
                map.put(Long.parseLong(parts[0]), Integer.parseInt(parts[1]));
            }
        }
        return map;
    }

    private String mapToString(HashMap<Long, Integer> map) {
        if (map == null || map.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, Integer> entry : map.entrySet()) {
            sb.append(entry.getKey()).append("-").append(entry.getValue()).append(",");
        }
        return sb.toString();
    }


}
