package io.mrarm.irc.view;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.design.widget.BottomSheetBehavior;
import android.support.v7.widget.RecyclerView;
import android.text.Editable;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Filter;

import java.util.ArrayList;
import java.util.List;

import io.mrarm.chatlib.dto.ModeList;
import io.mrarm.chatlib.dto.NickWithPrefix;
import io.mrarm.chatlib.irc.ServerConnectionApi;
import io.mrarm.chatlib.irc.ServerConnectionData;
import io.mrarm.irc.ServerConnectionInfo;
import io.mrarm.irc.chat.ChatSuggestionsAdapter;
import io.mrarm.irc.chat.CommandListSuggestionsAdapter;
import io.mrarm.irc.config.CommandAliasManager;
import io.mrarm.irc.config.SettingsHelper;
import io.mrarm.irc.util.CommandAliasSyntaxParser;
import io.mrarm.irc.util.SelectableRecyclerViewAdapter;
import io.mrarm.irc.util.SimpleTextWatcher;

public class ChatAutoCompleteEditText extends FormattableEditText implements
        SharedPreferences.OnSharedPreferenceChangeListener,
        ChatSuggestionsAdapter.OnItemClickListener {

    private static final int THRESHOLD = 2;

    private boolean mDoThresholdSuggestions;
    private boolean mDoAtSuggestions;
    private boolean mAtSuggestionsRemoveAt;
    private boolean mDoChannelSuggestions;

    private View mSuggestionsContainer;
    private View mSuggestionsCard;
    private RecyclerView mSuggestionsList;
    private ServerConnectionInfo mConnection;
    private ChatSuggestionsAdapter mAdapter;
    private CommandListSuggestionsAdapter mCommandAdapter;
    private ModeList mChannelTypes;
    private List<CommandAliasManager.CommandAlias> mCompletingCommands;
    private List<CharSequence> mHistory;
    private int mHistoryIndex;

    public ChatAutoCompleteEditText(Context context) {
        super(context);
        init();
    }

    public ChatAutoCompleteEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ChatAutoCompleteEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        addTextChangedListener(new SimpleTextWatcher((Editable s) -> {
            updateCompletingCommands();
            if (enoughToFilter())
                performFiltering(false);
            else
                dismissDropDown();
        }));

        onSharedPreferenceChanged(null, null);
    }

    public void setSuggestionsListView(View suggestionsContainer, View suggestionsCard, RecyclerView suggestionsList) {
        mSuggestionsContainer = suggestionsContainer;
        mSuggestionsCard = suggestionsCard;
        mSuggestionsList = suggestionsList;
    }

    public void setAdapter(ChatSuggestionsAdapter adapter) {
        mAdapter = adapter;
        mAdapter.setClickListener(this);
        mAdapter.setEnabledSuggestions(true, mDoChannelSuggestions, false);
    }

    public void setCommandListAdapter(CommandListSuggestionsAdapter adapter) {
        mCommandAdapter = adapter;
        mCommandAdapter.setClickListener(this);
    }

    public void setConnectionContext(ServerConnectionInfo info) {
        mConnection = info;
    }

    private void setCurrentCommandAdapter(boolean command) {
        if (command && mSuggestionsList.getAdapter() != mCommandAdapter)
            mSuggestionsList.setAdapter(mCommandAdapter);
        else if (!command && mSuggestionsList.getAdapter() != mAdapter)
            mSuggestionsList.setAdapter(mAdapter);
    }

    public void setChannelTypes(ModeList channelTypes) {
        mChannelTypes = channelTypes;
    }

    public void setHistory(List<CharSequence> mHistory) {
        this.mHistory = mHistory;
    }

    public void requestTabComplete() {
        performFiltering(true);
    }

    public void dismissDropDown() {
        mSuggestionsContainer.setVisibility(View.GONE);
        if (mSuggestionsList.getAdapter() instanceof SelectableRecyclerViewAdapter)
            ((SelectableRecyclerViewAdapter) mSuggestionsList.getAdapter()).setSelection(-1);
        mSuggestionsList.setAdapter(null);
        BottomSheetBehavior.from(mSuggestionsCard).setState(BottomSheetBehavior.STATE_COLLAPSED);
    }

    private void showDropDown() {
        mSuggestionsContainer.setVisibility(View.VISIBLE);
    }

    private void performFiltering(boolean completeIfSingle) {
        if (!updateCompletingCommandFlags())
            mAdapter.setEnabledSuggestions(true, mDoChannelSuggestions, false);
        final String text = getCurrentToken();
        Filter filter = isCommandNameToken() ? mCommandAdapter.getFilter() : mAdapter.getFilter();
        filter.filter(text, (int i) -> {
            if (i == 0) {
                dismissDropDown();
                return;
            }
            if (!getCurrentToken().equals(text) && !enoughToFilter())
                return;
            if (completeIfSingle && i == 1) {
                if (filter == mCommandAdapter.getFilter())
                    onItemClick(mCommandAdapter.getItem(0));
                else
                    onItemClick(mAdapter.getItem(0));
                return;
            }
            if (i > 0) {
                setCurrentCommandAdapter(filter == mCommandAdapter.getFilter());
                showDropDown();
            }
        });
    }

    private boolean enoughToFilter() {
        Editable s = getText();
        int end = getSelectionEnd();
        if (end < 0)
            return false;
        int start = findTokenStart();
        boolean hasAt = s.length() > start && s.charAt(start) == '@';
        updateCompletingCommandFlags();
        return (mDoThresholdSuggestions && end - start >= THRESHOLD &&
                (mDoAtSuggestions || !hasAt)) ||
                (mDoAtSuggestions && hasAt) ||
                (mDoChannelSuggestions && mChannelTypes != null && s.length() > start &&
                        mChannelTypes.contains(s.charAt(start))) ||
                isCommandNameToken() || updateCompletingCommandFlags();
    }

    private boolean isCommandNameToken() {
        return findTokenStart() == 0 && getText().length() > 0 && getText().charAt(0) == '/';
    }

    private void updateCompletingCommands() {
        String text = getText().toString();
        if (text.length() == 0 || text.charAt(0) != '/') {
            mCompletingCommands = null;
            return;
        }
        int iof = text.indexOf(' ');
        String currentCommand = iof != -1 ? text.substring(1, iof) : text;
        if (mCompletingCommands != null && mCompletingCommands.get(0).name.equalsIgnoreCase(currentCommand))
            return; // up to date
        mCompletingCommands = new ArrayList<>();
        for (CommandAliasManager.CommandAlias alias : CommandAliasManager.getDefaultAliases()) {
            if (alias.name.equalsIgnoreCase(currentCommand))
                mCompletingCommands.add(alias);
        }
        for (CommandAliasManager.CommandAlias alias : CommandAliasManager.getInstance(getContext()).getUserAliases()) {
            if (alias.name.equalsIgnoreCase(currentCommand))
                mCompletingCommands.add(alias);
        }
        if (mCompletingCommands.size() == 0)
            mCompletingCommands = null;
    }

    private boolean updateCompletingCommandFlags() {
        if (mCompletingCommands == null)
            return false;
        int end = getSelectionStart();
        String[] args = getText().toString().substring(0, end).split(" ", -1);
        if (args.length <= 1)
            return false;
        int flags = 0;
        for (CommandAliasManager.CommandAlias alias : mCompletingCommands) {
            if (alias.disableArgAutocomplete || alias.getSyntaxParser() == null)
                continue;
            ServerConnectionData data = ((ServerConnectionApi) mConnection.getApiInstance()).getServerConnectionData();
            flags |= alias.getSyntaxParser().getAutocompleteFlags(data, args, 1);
        }
        if (flags != 0)
            mAdapter.setEnabledSuggestions((flags & CommandAliasSyntaxParser.AUTOCOMPLETE_MEMBERS) != 0, (flags & CommandAliasSyntaxParser.AUTOCOMPLETE_CHANNELS) != 0, (flags & CommandAliasSyntaxParser.AUTOCOMPLETE_USERS) != 0);
        return flags != 0;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        SettingsHelper s = SettingsHelper.getInstance(getContext());
        s.addPreferenceChangeListener(SettingsHelper.PREF_NICK_AUTOCOMPLETE_SUGGESTIONS, this);
        s.addPreferenceChangeListener(SettingsHelper.PREF_NICK_AUTOCOMPLETE_AT_SUGGESTIONS, this);
        s.addPreferenceChangeListener(SettingsHelper.PREF_NICK_AUTOCOMPLETE_AT_SUGGESTIONS_REMOVE_AT, this);
        s.addPreferenceChangeListener(SettingsHelper.PREF_CHANNEL_AUTOCOMPLETE_SUGGESTIONS, this);
        onSharedPreferenceChanged(null, null);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        SettingsHelper s = SettingsHelper.getInstance(getContext());
        s.removePreferenceChangeListener(SettingsHelper.PREF_NICK_AUTOCOMPLETE_SUGGESTIONS, this);
        s.removePreferenceChangeListener(SettingsHelper.PREF_NICK_AUTOCOMPLETE_AT_SUGGESTIONS, this);
        s.removePreferenceChangeListener(SettingsHelper.PREF_NICK_AUTOCOMPLETE_AT_SUGGESTIONS_REMOVE_AT, this);
        s.removePreferenceChangeListener(SettingsHelper.PREF_CHANNEL_AUTOCOMPLETE_SUGGESTIONS, this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        SettingsHelper s = SettingsHelper.getInstance(getContext());
        mDoThresholdSuggestions = s.shouldShowNickAutocompleteSuggestions();
        mDoAtSuggestions = s.shouldShowNickAutocompleteAtSuggestions();
        mAtSuggestionsRemoveAt = s.shouldRemoveAtWithNickAutocompleteAtSuggestions();
        mDoChannelSuggestions = s.shouldShowChannelAutocompleteSuggestions();
        if (mAdapter != null)
            mAdapter.setEnabledSuggestions(true, mDoChannelSuggestions, false);
    }

    @Override
    public void onItemClick(Object item) {
        CharSequence val;
        if (item instanceof NickWithPrefix) {
            val = terminateNickToken(((NickWithPrefix) item).getNick());
        } else if (item instanceof CommandAliasManager.CommandAlias) {
            val = "/" + ((CommandAliasManager.CommandAlias) item).name + " ";
        } else {
            val = item.toString() + " ";
        }
        int start = findTokenStart();
        int end = findTokenEnd();
        clearComposingText();
        // QwertyKeyListener.markAsReplaced(getText(), start, end, TextUtils.substring(getText(), start, end));
        getText().replace(start, end, val);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_TAB) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (mSuggestionsList.getAdapter() != null &&
                        mSuggestionsList.getAdapter() instanceof SelectableRecyclerViewAdapter) {
                    int i = ((SelectableRecyclerViewAdapter) mSuggestionsList.getAdapter()).
                            getSelection();
                    if (i != -1) {
                        if (mSuggestionsList.getAdapter() == mAdapter)
                            onItemClick(mAdapter.getItem(i));
                        else if (mSuggestionsList.getAdapter() == mCommandAdapter)
                            onItemClick(mCommandAdapter.getItem(i));
                    }
                    return true;
                }
                mAdapter.setSelection(0);
                requestTabComplete();
            }
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP ||
                event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (mSuggestionsList.getAdapter() != null &&
                        mSuggestionsList.getAdapter() instanceof SelectableRecyclerViewAdapter) {
                    SelectableRecyclerViewAdapter adapter = (SelectableRecyclerViewAdapter)
                            mSuggestionsList.getAdapter();
                    if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN)
                        adapter.setSelection(Math.min(adapter.getSelection() + 1, adapter.getItemCount() - 1));
                    else
                        adapter.setSelection(Math.max(adapter.getSelection() - 1, 0));
                    mSuggestionsList.scrollToPosition(adapter.getSelection());
                    return true;
                }
                int i = mHistoryIndex;
                if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (i == -1)
                        return true;
                    i--;
                } else {
                    i = Math.min(i + 1, mHistory.size() - 1);
                }
                if (i == -1)
                    setText("");
                else
                    setText(mHistory.get(mHistory.size() - 1 - i));
                setSelection(getText().length());
                mHistoryIndex = i;
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        super.setText(text, type);
        mHistoryIndex = -1;
    }

    public int findTokenStart() {
        int cursor = getSelectionStart();
        for (int start = cursor; start > 0; start--) {
            char c = getText().charAt(start - 1);
            if (c == ' ' || c == ',')
                return start;
        }
        return 0;
    }

    public int findTokenEnd() {
        int len = getText().length();
        int cursor = getSelectionStart();
        for (int end = cursor; end < len; end++) {
            char c = getText().charAt(end);
            if (c == ' ' || c == ',' || c == ':')
                return end;
        }
        return len;
    }

    private String getCurrentToken() {
        int start = findTokenStart();
        int end = getSelectionEnd();
        return getText().toString().substring(start, end);
    }

    public CharSequence terminateNickToken(CharSequence text) {
        int start = findTokenStart();
        if (!mAtSuggestionsRemoveAt &&
                (getText().length() > start && getText().charAt(start) == '@'))
            return "@" + text + " ";
        if (start == 0)
            return text + ": ";
        return text + " ";
    }

}
