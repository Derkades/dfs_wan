package eclipfs.metaserver.servlet.node;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;

import eclipfs.metaserver.Database;
import eclipfs.metaserver.model.Node;
import eclipfs.metaserver.servlet.HttpUtil;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class CheckGarbage extends HttpServlet {

	private static final long serialVersionUID = 1L;

	@Override
	protected void doPost(final HttpServletRequest request, final HttpServletResponse response) throws IOException {
		try {
			final Optional<Node> optNode = NodeAuthentication.verify(request, response);
			if (optNode.isEmpty()) {
				return;
			}

			final Node node = optNode.get();

			final JsonObject json = HttpUtil.readJsonFromRequestBody(request, response);
			if (json == null) {
				return;
			}

			final JsonArray chunks = json.getAsJsonArray("chunks");

			try (JsonWriter writer = HttpUtil.getJsonWriter(response);
					Connection conn = Database.getConnection()) {
				writer.beginObject().name("garbage").beginArray();
				for (final JsonElement e : chunks) {
					try (final PreparedStatement query = conn.prepareStatement("SELECT node FROM chunk_node JOIN chunk ON chunk=id WHERE node=? AND token=?")) {
						query.setLong(1, node.getId());
						query.setString(2, e.getAsString());
						final ResultSet result = query.executeQuery();
						if (!result.next()) {
							writer.value(e.getAsString());
						}
					}
				}
				writer.endArray().endObject();
			}
		} catch (final SQLException e) {
			HttpUtil.handleSqlException(response, e);
		}
	}

}