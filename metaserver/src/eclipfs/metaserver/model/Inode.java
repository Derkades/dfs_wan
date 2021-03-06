package eclipfs.metaserver.model;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import org.apache.commons.lang3.Validate;

import eclipfs.metaserver.Database;
import eclipfs.metaserver.Validation;
import eclipfs.metaserver.exception.AlreadyExistsException;
import eclipfs.metaserver.exception.NotADirectoryException;

public abstract class Inode {

	static final long ROOT_INODE = 1;

	private final long id;
	private long parentId;
	private String name;
	private long ctime;
	private long mtime;
	protected Integer chunkSize;
	private long size;

	protected Inode(final ResultSet result) throws SQLException {
		this.id = result.getLong("id");
		this.parentId = result.getLong("parent");
		this.name = result.getString("name");
		this.ctime = result.getLong("ctime");
		this.mtime = result.getLong("mtime");
		this.chunkSize = result.getObject("chunk_size", Integer.class);
		this.size = result.getLong("size");
	}

	public abstract boolean isFile();
	public abstract void delete() throws SQLException;

	public final long getId() {
		return this.id;
	}

	public boolean isRootDirectory() {
		return this.getId() == ROOT_INODE;
	}

	public final long getParentId() {
		return this.parentId;
	}

	public Directory getParent() throws SQLException {
		if (this.isRootDirectory()) {
			throw new UnsupportedOperationException("Cannot get parent for root inode");
		}

		return (Directory) byId(this.getParentId()).get();
	}

	public final String getName() {
		return this.name;
	}

	public final long getCreationTime() {
		return this.ctime;
	}

	public final long getModificationTime() {
		return this.mtime;
	}

	public final void setCreationTime(final long ctime) throws SQLException {
		try (Connection conn = Database.getConnection();
				PreparedStatement query = conn.prepareStatement("UPDATE inode SET ctime=? WHERE id=?")) {
			query.setLong(1, ctime);
			query.setLong(2, this.getId());
			query.execute();
			this.ctime = ctime;
		}
	}

	public final void setModificationTime(final long mtime) throws SQLException {
		try (Connection conn = Database.getConnection();
				PreparedStatement query = conn.prepareStatement("UPDATE inode SET mtime=? WHERE id=?")) {
			query.setLong(1, mtime);
			query.setLong(2, this.getId());
			query.execute();
			this.mtime = mtime;
		}
	}

	public long getSize() {
		return this.size;
	}

	public void setSize(final long size) throws SQLException {
		try (Connection conn = Database.getConnection();
				PreparedStatement query = conn.prepareStatement("UPDATE inode SET size=? WHERE id=?")) {
			query.setLong(1, size);
			query.setLong(2, this.getId());
			query.execute();
			this.size = size;
		}
	}

	public String getAbsolutePath() throws SQLException {
		if (this.id == 0) {
			return "";
		}

		String path = "";
		Inode inode = this;
		while (!inode.isRootDirectory()) {
			path = "/" + inode.getName() + path;
			inode = inode.getParent();
		}
		return path;
	}

	@Override
	public boolean equals(final Object other) {
		return other != null && other instanceof Inode && ((Inode) other).getId() == this.getId();
	}

	@Override
	public int hashCode() {
		return Long.hashCode(this.id);
	}

	public static int fileCount() throws SQLException {
		try (Connection conn = Database.getConnection();
				PreparedStatement query = conn.prepareStatement("SELECT COUNT(*) FROM inode WHERE is_file='True'")) {
			final ResultSet result = query.executeQuery();
			result.next();
			return result.getInt(1);
		}
	}

	public static int directoryCount() throws SQLException {
		try (Connection conn = Database.getConnection();
				PreparedStatement query = conn.prepareStatement("SELECT COUNT(*) FROM inode WHERE is_file='False'")) {
			final ResultSet result = query.executeQuery();
			result.next();
			return result.getInt(1);
		}
	}

	public static int inodeCount() throws SQLException {
		try (Connection conn = Database.getConnection();
				PreparedStatement query = conn.prepareStatement("SELECT COUNT(*) FROM inode")) {
			final ResultSet result = query.executeQuery();
			result.next();
			return result.getInt(1);
		}
	}

	public static Optional<Inode> byId(final long id) throws SQLException {
		Validate.isTrue(id >= 0, "inode must be >= 0");
		try (Connection conn = Database.getConnection();
				PreparedStatement query = conn.prepareStatement("SELECT * FROM inode WHERE id=?")) {
			query.setLong(1, id);
			return optInodeFromResult(query.executeQuery());
		}
	}

	protected static Optional<Inode> optInodeFromResult(final ResultSet result) throws SQLException {
		if (!result.next()) {
			return Optional.empty();
		}
		if (result.getBoolean("is_file")) {
			return Optional.of(new File(result));
		} else {
			return Optional.of(new Directory(result));
		}
	}

	public static boolean isFile(final long inode) throws SQLException {
		try (Connection conn = Database.getConnection();
				PreparedStatement query = conn.prepareStatement("SELECT * FROM inode WHERE id=? AND is_file = 'True'")) {
			query.setLong(1, inode);
			return query.executeQuery().next();
		}
	}

	public static boolean isDirectory(final long inode) throws SQLException {
		try (Connection conn = Database.getConnection();
				PreparedStatement query = conn.prepareStatement("SELECT * FROM inode WHERE id=? AND is_file = 'False'")) {
			query.setLong(1, inode);
			return query.executeQuery().next();
		}
	}

	public static Optional<Inode> findByPath(final String path) throws SQLException, NotADirectoryException {
		Validation.validatePath(path);
		final String[] pathComponents = path.split("/");
		// This should never happen. First component is always blank, second component has to exist
		Validate.isTrue(pathComponents.length > 1, "path split by / length too short, path: '" + path + "'");

		Directory parent = getRootInode();

		for (int i = 1; i < pathComponents.length - 1; i++) {
			final String pathComponent = pathComponents[i];
			// Everything before the last component has to be a directory
			final Optional<Inode> opt = parent.getChild(pathComponent);
			if (opt.isEmpty()) {
				return Optional.empty();
			}
			final Inode inode = opt.get();
			if (inode.isFile()) {
				throw new NotADirectoryException(inode);
			} else {
				parent = (Directory) inode;
			}
		}

		return parent.getChild(pathComponents[pathComponents.length - 1]);
	}

	public static Directory getRootInode() throws SQLException {
		// TODO cache
		return (Directory) byId(ROOT_INODE).get();
	}

	public void move(final Directory newParent, final String newName) throws SQLException, AlreadyExistsException {
		Validate.notNull(newParent);
		Validate.notNull(newName);

		if (newParent.contains(newName)) {
			throw new AlreadyExistsException("A file or directory with the name " + newName + " already exists");
		}

		try (Connection conn = Database.getConnection();
				final PreparedStatement query = conn.prepareStatement("UPDATE inode SET parent=?, name=? WHERE id=?")){
			query.setLong(1, newParent.getId());
			query.setString(2, newName);
			query.setLong(3, this.getId());
			query.execute();
			this.parentId = newParent.getId();
			this.name = newName;
		}
	}
}
