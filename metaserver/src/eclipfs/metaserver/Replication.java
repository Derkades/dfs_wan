package eclipfs.metaserver;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eclipfs.metaserver.Nodes.FilterStrategy;
import eclipfs.metaserver.model.Chunk;
import eclipfs.metaserver.model.Node;
import eclipfs.metaserver.model.OnlineNode;

public class Replication {

	private static final Logger LOGGER = LoggerFactory.getLogger("Replication");

	private static long lastBusyTime = 0;

	public static void signalBusy() {
		lastBusyTime = System.currentTimeMillis();
	}

	private static boolean isBusy() {
		return System.currentTimeMillis() - lastBusyTime < Tunables.REPLICATION_IDLE_WAIT;
	}

	// for dashboard
	public static String getStatus() {
		if (isBusy()) {
			return "Waiting (transfers in progress)";
		}

		if (QUEUE.isEmpty()) {
			return "Idle (nothing to do)";
		}

		return "Running";
	}

	private final static Deque<Long> QUEUE = new ArrayDeque<>();

	// for dashboard
	public static int getQueueSize() {
		return QUEUE.size();
	}

	static void run() {
		while(true) {
			try {
				Thread.sleep(Tunables.REPLICATION_DELAY);

				if (isBusy()) {
					continue;
				}

				if (QUEUE.isEmpty()) {
					final long start = System.currentTimeMillis();
					addUndergoalChunks(QUEUE, Tunables.REPLICATION_ADD_AMOUNT);
					final long time = System.currentTimeMillis() - start;
					if (QUEUE.isEmpty()) {
						LOGGER.info("Queue still empty, going to sleep for a while (took " + time + "ms to find chunks).");
						Thread.sleep(Tunables.REPLICATION_EMPTY_SLEEP);
					} else {
						LOGGER.info("Added " + QUEUE.size() + " chunks to the replication queue (took " + time + "ms).");
					}
					continue;
				}

				LOGGER.info("Processing replication queue, " + QUEUE.size() + " entries left.");

				final long chunkId = QUEUE.pop();
				final Optional<Chunk> optChunk = Chunk.byId(chunkId);
				if (optChunk.isEmpty()) {
					LOGGER.warn("Skipping chunk " + chunkId + ", it has been deleted.");
					continue;
				}
				final Chunk chunk = optChunk.get();
				final List<Node> nodes = chunk.getNodes();
				final Set<String> existingLabels = nodes.stream().map(Node::getLocation).distinct().collect(Collectors.toSet());
				final int replication = existingLabels.size();
				final String chunkStr = chunk.getFile().getId() + "." + chunk.getIndex();
				if (replication > Tunables.REPLICATION_GOAL) {
					LOGGER.warn("Chunk " + chunkStr + " is overgoal");
					continue;
				} else if (replication == Tunables.REPLICATION_GOAL) {
					LOGGER.warn("Chunk " + chunkStr + " is replicated correctly");
					continue;
				}

				LOGGER.info("Chunk " + chunkStr + " is undergoal (" + replication + "/" + Tunables.REPLICATION_GOAL + ")");
				final Optional<OnlineNode> optReplicationTarget = Nodes.selectNode(chunk, TransferType.UPLOAD, FilterStrategy.MUST_NOT, existingLabels);
				if (optReplicationTarget.isEmpty()) {
					LOGGER.warn("Cannot replicate chunk, no target node available. Current labels: " + String.join(", ", existingLabels));
					continue;
				}

				final OnlineNode replicationTarget = optReplicationTarget.get();
				final Optional<OnlineNode> optReplicationSource = Nodes.selectNode(chunk, TransferType.DOWNLOAD);
				if (optReplicationSource.isEmpty()) {
					LOGGER.warn("Cannot replicate chunk, no source node available.");
					continue;
				}

				final OnlineNode replicationSource = optReplicationSource.get();
				if (replicationTarget.requestReplicate(chunk, replicationSource, LOGGER)) {
					LOGGER.info("Successfully replicated chunk.");
				}
				chunk.addNode(replicationTarget);
			} catch (final Exception e) {
				e.printStackTrace();
			}
		}
	}

//	public static void addRandomChunksToQueue(final int amount) throws SQLException {
//		if (CHUNK_CHECK_QUEUE.size() > amount) {
//			LOGGER.info("Chunk queue is already quite full, not adding random chunks");
//			return;
//		}
//		try (Connection conn = Database.getConnection();
//				PreparedStatement query = conn.prepareStatement("SELECT id FROM chunk TABLESAMPLE SYSTEM_ROWS(?)")){
//			query.setInt(1, amount);
//			final ResultSet result = query.executeQuery();
//			while (result.next()) {
//				addToCheckQueue(result.getLong(1));
//			}
//		}
//	}

	public static void addUndergoalChunks(final Deque<Long> queue, final int limit) throws SQLException {
		try (Connection conn = Database.getConnection();
//				PreparedStatement query = conn.prepareStatement("SELECT id FROM chunk JOIN chunk_node ON id=chunk GROUP BY chunk.id HAVING COUNT(node) < ? LIMIT ?")) {
				PreparedStatement query = conn.prepareStatement("SELECT chunk.id \n"
						+ "FROM chunk \n"
						+ "	JOIN chunk_node ON chunk=chunk.id \n"
						+ "	JOIN node ON node=node.id \n"
						+ "GROUP BY chunk.id \n"
						+ "HAVING COUNT(DISTINCT node.location) < ? LIMIT ?")) {
				query.setInt(1, Tunables.REPLICATION_GOAL);
			query.setInt(2, limit);
			final ResultSet result = query.executeQuery();
			while (result.next()) {
				queue.add(result.getLong(1));
			}
		}
	}

}
