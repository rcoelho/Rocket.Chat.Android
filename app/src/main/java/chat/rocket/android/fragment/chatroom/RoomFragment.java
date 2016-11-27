package chat.rocket.android.fragment.chatroom;

import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import chat.rocket.android.R;
import chat.rocket.android.helper.LoadMoreScrollListener;
import chat.rocket.android.helper.LogcatIfError;
import chat.rocket.android.layouthelper.chatroom.MessageListAdapter;
import chat.rocket.android.model.ServerConfig;
import chat.rocket.android.model.SyncState;
import chat.rocket.android.model.ddp.Message;
import chat.rocket.android.model.ddp.RoomSubscription;
import chat.rocket.android.model.internal.LoadMessageProcedure;
import chat.rocket.android.realm_helper.RealmHelper;
import chat.rocket.android.realm_helper.RealmObjectObserver;
import chat.rocket.android.realm_helper.RealmStore;
import chat.rocket.android.service.RocketChatService;
import io.realm.Sort;
import org.json.JSONObject;
import timber.log.Timber;

/**
 * Chat room screen.
 */
public class RoomFragment extends AbstractChatRoomFragment {

  private RealmHelper realmHelper;
  private String roomId;
  private RealmObjectObserver<RoomSubscription> roomObserver;
  private String hostname;
  private LoadMoreScrollListener scrollListener;
  private RealmObjectObserver<LoadMessageProcedure> procedureObserver;

  /**
   * create fragment with roomId.
   */
  public static RoomFragment create(String serverConfigId, String roomId) {
    Bundle args = new Bundle();
    args.putString("serverConfigId", serverConfigId);
    args.putString("roomId", roomId);
    RoomFragment fragment = new RoomFragment();
    fragment.setArguments(args);
    return fragment;
  }

  public RoomFragment() {
  }

  @Override public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    Bundle args = getArguments();
    String serverConfigId = args.getString("serverConfigId");
    realmHelper = RealmStore.get(serverConfigId);
    roomId = args.getString("roomId");
    hostname = RealmStore.getDefault().executeTransactionForRead(realm ->
        realm.where(ServerConfig.class)
            .equalTo("serverConfigId", serverConfigId)
            .isNotNull("hostname")
            .findFirst()).getHostname();
    roomObserver = realmHelper
        .createObjectObserver(realm -> realm.where(RoomSubscription.class).equalTo("rid", roomId))
        .setOnUpdateListener(this::onRenderRoom);

    procedureObserver = realmHelper
        .createObjectObserver(realm ->
            realm.where(LoadMessageProcedure.class).equalTo("roomId", roomId))
        .setOnUpdateListener(this::onUpdateLoadMessageProcedure);
    if (savedInstanceState == null) {
      initialRequest();
    }
  }

  @Override protected int getLayout() {
    return R.layout.fragment_room;
  }

  @Override protected void onSetupView() {
    RecyclerView listView = (RecyclerView) rootView.findViewById(R.id.recyclerview);
    listView.setAdapter(realmHelper.createListAdapter(getContext(),
        realm -> realm.where(Message.class)
            .equalTo("rid", roomId)
            .findAllSorted("ts", Sort.DESCENDING),
        context -> new MessageListAdapter(context, hostname)
    ));

    LinearLayoutManager layoutManager = new LinearLayoutManager(getContext(),
        LinearLayoutManager.VERTICAL, true);
    listView.setLayoutManager(layoutManager);

    scrollListener = new LoadMoreScrollListener(layoutManager, 40) {
      @Override public void requestMoreItem() {
        loadMoreRequest();
      }
    };
    listView.addOnScrollListener(scrollListener);
  }

  private void onRenderRoom(RoomSubscription roomSubscription) {
    activityToolbar.setTitle(roomSubscription.getName());
  }

  private void onUpdateLoadMessageProcedure(LoadMessageProcedure procedure) {
    if (procedure == null) {
      return;
    }
    RecyclerView listView = (RecyclerView) rootView.findViewById(R.id.recyclerview);
    if (listView != null && listView.getAdapter() instanceof MessageListAdapter) {
      MessageListAdapter adapter = (MessageListAdapter) listView.getAdapter();
      final int syncstate = procedure.getSyncstate();
      final boolean hasNext = procedure.isHasNext();
      Timber.d("hasNext: %s syncstate: %d", hasNext, syncstate);
      if (syncstate == SyncState.SYNCED || syncstate == SyncState.FAILED) {
        scrollListener.setLoadingDone();
        adapter.updateFooter(hasNext, true);
      } else {
        adapter.updateFooter(hasNext, false);
      }
    }
  }

  private void initialRequest() {
    realmHelper.executeTransaction(realm -> {
      realm.createOrUpdateObjectFromJson(LoadMessageProcedure.class, new JSONObject()
          .put("roomId", roomId)
          .put("syncstate", SyncState.NOT_SYNCED)
          .put("count", 100)
          .put("reset", true));
      return null;
    }).onSuccessTask(task -> {
      RocketChatService.keepalive(getContext());
      return task;
    }).continueWith(new LogcatIfError());
  }

  private void loadMoreRequest() {
    realmHelper.executeTransaction(realm -> {
      LoadMessageProcedure procedure = realm.where(LoadMessageProcedure.class)
          .equalTo("roomId", roomId)
          .beginGroup()
          .equalTo("syncstate", SyncState.SYNCED)
          .or()
          .equalTo("syncstate", SyncState.FAILED)
          .endGroup()
          .equalTo("hasNext", true)
          .findFirst();
      if (procedure != null) {
        procedure.setSyncstate(SyncState.NOT_SYNCED);
      }
      return null;
    }).onSuccessTask(task -> {
      RocketChatService.keepalive(getContext());
      return task;
    }).continueWith(new LogcatIfError());
  }

  @Override public void onResume() {
    super.onResume();
    roomObserver.sub();
    procedureObserver.sub();
  }

  @Override public void onPause() {
    procedureObserver.unsub();
    roomObserver.unsub();
    super.onPause();
  }
}
