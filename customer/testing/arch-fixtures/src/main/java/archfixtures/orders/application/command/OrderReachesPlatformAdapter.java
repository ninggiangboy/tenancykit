package archfixtures.orders.application.command;

import archfixtures.platform.adapter.platform.persistence.ReplicaDirectoryStub;

/** Violates contexts-do-not-reach-platform-adapters: a context names another context's adapter. */
public class OrderReachesPlatformAdapter {

    public String code(ReplicaDirectoryStub directory) {
        return directory.code();
    }
}
