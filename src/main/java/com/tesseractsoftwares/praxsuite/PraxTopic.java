package com.tesseractsoftwares.praxsuite;

/**
 * A topic - a namespace of buses. {@code bus.topic("office").channel("hq")} is {@code office:hq}.
 *
 * <p>Topics are admin-declared workspace configuration, not something a client invents: the hub
 * refuses a bus key whose topic was never declared, which is what stops another application's
 * client squatting in your namespace. Declare one in the portal under API Gateway / Event Bus and
 * pick its access rule there.
 */
public final class PraxTopic {

    private final PraxBus bus;
    private final String key;

    PraxTopic(PraxBus bus, String key) {
        this.bus = bus;
        this.key = key;
    }

    /** The topic segment, already folded to lowercase the way the server folds it. */
    public String key() {
        return key;
    }

    /** The bus for one instance of this topic. */
    public PraxChannel channel(String instance) {
        return bus.channel(key + ":" + instance);
    }
}
