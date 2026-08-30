package gg.rc.parties;

import gg.rc.parties.api.event.PartyEvent;
import gg.rc.parties.api.event.PartyInviteEvent;
import gg.rc.parties.internal.PartyEventSink;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Collects fired events so tests can assert on the event surface without a live server. */
final class RecordingEventSink implements PartyEventSink {

    private final List<PartyEvent> fired = new ArrayList<>();
    private Predicate<PartyInviteEvent> cancelInvites = invite -> false;

    void cancelInvitesWhen(Predicate<PartyInviteEvent> predicate) {
        this.cancelInvites = predicate;
    }

    @Override
    public void fire(PartyEvent event) {
        fired.add(event);
    }

    @Override
    public boolean fireCancellable(PartyInviteEvent event) {
        fired.add(event);
        if (cancelInvites.test(event)) {
            event.setCancelled(true);
        }
        return event.isCancelled();
    }

    <E extends PartyEvent> List<E> of(Class<E> type) {
        return fired.stream().filter(type::isInstance).map(type::cast).toList();
    }

    <E extends PartyEvent> E last(Class<E> type) {
        List<E> matches = of(type);
        if (matches.isEmpty()) {
            throw new AssertionError("No " + type.getSimpleName() + " was fired");
        }
        return matches.get(matches.size() - 1);
    }

    int count(Class<? extends PartyEvent> type) {
        return of(type).size();
    }

    void clear() {
        fired.clear();
    }
}
