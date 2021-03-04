package dsn_metaserver.command;

import java.util.Optional;

import dsn_metaserver.model.Node;

public class NodeRemoveCommand extends Command {

	@Override
	public void run(final String[] args) throws Exception {
		final String token = args[0];
		final Optional<Node> node = Node.findByTokenInDatabase(token);
		if (node.isEmpty()) {
			System.out.println("Node not found");
		} else {
			Node.deleteNode(node.get());
			System.out.println("Node deleted");
		}
	}

}
