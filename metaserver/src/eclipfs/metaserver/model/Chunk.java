package eclipfs.metaserver.model;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.lang.Validate;
import org.springframework.security.crypto.codec.Hex;

import eclipfs.metaserver.Database;

public class Chunk {
	
	private final long id;
	private final int index;
	private long size;
	private final byte[] checksum;
	private final String token;
	
	private transient final File file;

	Chunk(final File file, final ResultSet result) throws SQLException {
		Validate.notNull(file, "File is null");
		Validate.notNull(result, "ResultSet is null");
		this.id = result.getLong("id");
		this.index = result.getInt("index");
		this.size = result.getLong("size");
		Validate.isTrue(result.getLong("file") == file.getId());
		this.checksum = result.getBytes("checksum");
		this.token = result.getString("token");
		this.file = file;
	}
	
	public long getId() {
		return this.id;
	}
	
	public int getIndex() {
		return this.index;
	}
	
	public long getSize() {
		return this.size;
	}
	
	public void setSize(final long size) throws SQLException {
		try (Connection conn = Database.getConnection();
				PreparedStatement query = conn.prepareStatement("UPDATE chunk SET size=? WHERE id=?")) {
			query.setLong(1, size);
			query.setLong(2, this.id);
			query.execute();
			this.size = size;
		}
	}
	
	public File getFile() {
		return this.file;
	}
	
	public byte[] getChecksum() {
		return this.checksum;
	}
	
	public String getChecksumHex() {
		return new String(Hex.encode(this.getChecksum()));
	}
	
	public String getToken() {
		return this.token;
	}
	
	public List<Long> getNodeIds() throws SQLException {
		try (Connection conn = Database.getConnection();
				PreparedStatement query = conn.prepareStatement("SELECT node FROM \"chunk_node\" WHERE chunk=?")) {
			query.setLong(1, this.getId());
			final ResultSet result = query.executeQuery();
			final List<Long> nodes = new ArrayList<>();
			while (result.next()) {
				nodes.add(result.getLong(1));
			}
			return nodes;
		}
	}
	
	public List<Node> getNodes() throws SQLException {
		final List<Node> nodes = new ArrayList<>();
		for (final long id : getNodeIds()) {
			nodes.add(Node.byId(id).orElseThrow(() -> new IllegalStateException("Node id " + id + " not found, this should be impossible due to foreign key constraints")));
		}
		return nodes;
	}
	
	public void updateChecksum(final byte[] checksum) throws SQLException {
		try (Connection conn = Database.getConnection();
				PreparedStatement query = conn.prepareStatement("UPDATE \"chunk\" SET checksum=? WHERE id=?")){
			query.setBytes(1, checksum);
			query.setLong(2, this.getId());
			query.execute();
		}
	}
	
	public void updateSize(final long size) throws SQLException {
		try (Connection conn = Database.getConnection();
				PreparedStatement query = conn.prepareStatement("UPDATE \"chunk\" SET size=? WHERE id=?")){
			query.setLong(1, size);
			query.setLong(2, this.getId());
			query.execute();
		}
	}
	
	public List<OnlineNode> getOnlineNodes() throws SQLException {
		return getNodeIds().stream()
				.map(OnlineNode::getOnlineNodeById)
				.filter(Optional::isPresent).map(Optional::get)
				.collect(Collectors.toUnmodifiableList());
	}
	
	public void addNode(final Node node) throws SQLException {
		Validate.notNull(node, "Node is null");
		try (Connection conn = Database.getConnection()){
			try (PreparedStatement query = conn.prepareStatement("SELECT node FROM chunk_node WHERE node=? AND chunk=?")) {
				query.setLong(1, node.getId());
				query.setLong(2, this.getId());
				if (query.executeQuery().next()) {
					System.out.println("Ignoring addnode, node already has chunk");
					return;
				}
			}
			
			try (PreparedStatement query = conn.prepareStatement("INSERT INTO chunk_node(chunk, node) VALUES (?, ?)")) {
				query.setLong(1, this.getId());
				query.setLong(2, node.getId());
				query.execute();
			}
		}
				
	}
	
	public static Optional<Chunk> byId(final long id) throws SQLException {
		try (Connection conn = Database.getConnection();
				PreparedStatement query = conn.prepareStatement("SELECT * FROM \"chunk\" WHERE id=?")) {
			query.setLong(1, id);
			final ResultSet result = query.executeQuery();
			if (result.next()) {
				final long fileId = result.getLong("file");
				final Optional<Inode> optFile = Inode.byId(fileId);
				if (optFile.isEmpty()) {
					throw new IllegalStateException("Orphan chunk: file no longer exists. File id: " + fileId);
				}
				return Optional.of(new Chunk((File) optFile.get(), result));
			} else {
				return Optional.empty();
			}
		}
	}
	
	public static Optional<Chunk> findByToken(final String chunkToken) throws SQLException {
		Validate.notNull(chunkToken, "Chunk token is null");
		
		try (Connection conn = Database.getConnection();
				PreparedStatement query = conn.prepareStatement("SELECT * FROM \"chunk\" WHERE token=?")) {
			query.setString(1, chunkToken);
			final ResultSet result = query.executeQuery();
			if (result.next()) {
				final long fileId = result.getLong("file");
				final Optional<Inode> optFile = Inode.byId(fileId);
				if (optFile.isEmpty()) {
					throw new IllegalStateException("Orphan chunk: file no longer exists. File id: " + fileId);
				}
				return Optional.of(new Chunk((File) optFile.get(), result));
			} else {
				return Optional.empty();
			}
		}
	}
	
//	@Deprecated
//	public long calculateExceptedSize() {
////		final int chunkSize = this.file.getChunkSize();
//		final long totalFileSize = this.file.getSize();
//		final int chunkCount = this.file.calculateExpectedChunkCount();
//		if (this.index == chunkCount - 1) {
//			// Last (or only) chunk, may be filled partially
//			final long partial = totalFileSize % Tunables.CHUNKSIZE;
//			return partial;
//		} else {
//			// Not last chunk, max chunk size
//			return Tunables.CHUNKSIZE;
//		}
//	}

}