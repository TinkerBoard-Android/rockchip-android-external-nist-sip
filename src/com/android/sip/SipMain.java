/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.sip;

import android.net.sip.SdpSessionDescription;
import android.net.sip.SessionDescription;
import android.net.sip.SipManager;
import android.net.sip.SipProfile;
import android.net.sip.SipSession;
import android.net.sip.SipSessionListener;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.preference.Preference.OnPreferenceClickListener;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.text.ParseException;
import javax.sip.SipException;

/**
 */
public class SipMain extends PreferenceActivity
        implements Preference.OnPreferenceChangeListener {
    private static final String TAG = SipMain.class.getName();
    private static final int MENU_REGISTER = Menu.FIRST;
    private static final int MENU_CALL = Menu.FIRST + 1;
    private static final int MENU_HANGUP = Menu.FIRST + 2;

    private Preference mCallStatus;
    private EditTextPreference mPeerUri;
    private EditTextPreference mServerUri;
    private EditTextPreference mPassword;
    private EditTextPreference mDisplayName;
    private EditTextPreference mLocalMediaPort;
    private Preference mMyIp;

    private SipManager mSipManager;
    private SipSession mSipSession;
    private SipSession mSipCallSession;
    private boolean mHolding = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.dev_pref);

        mCallStatus = getPreferenceScreen().findPreference("call_status");
        mPeerUri = setupEditTextPreference("peer");
        mServerUri = setupEditTextPreference("server_address");
        mPassword = (EditTextPreference)
                getPreferenceScreen().findPreference("password");
        mDisplayName = setupEditTextPreference("display_name");
        mLocalMediaPort = setupEditTextPreference("local_media_port");
        mMyIp = getPreferenceScreen().findPreference("my_ip");

        mMyIp.setSummary(getLocalIp());
        mMyIp.setOnPreferenceClickListener(
                new OnPreferenceClickListener() {
                    public boolean onPreferenceClick(Preference preference) {
                        // for testing convenience: copy my IP to server address
                        if (TextUtils.isEmpty(mServerUri.getText())) {
                            String myIp = mMyIp.getSummary().toString();
                            String uri = "test@" + myIp + ":5060";
                            mServerUri.setText(uri);
                            mServerUri.setSummary(uri);
                        }
                        return true;
                    }
                });

        mCallStatus.setOnPreferenceClickListener(
                new OnPreferenceClickListener() {
                    public boolean onPreferenceClick(Preference preference) {
                        actOnCallStatus();
                        return true;
                    }
                });

        setCallStatus();
    }

    private EditTextPreference setupEditTextPreference(String key) {
        EditTextPreference preference = (EditTextPreference)
                getPreferenceScreen().findPreference(key);
        preference.setOnPreferenceChangeListener(this);
        preference.setSummary(preference.getText());
        return preference;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mSipManager != null) {
            mSipManager.release();
            mSipManager = null;
            mSipSession = null;
        }
    }

    public boolean onPreferenceChange(Preference pref, Object newValue) {
        String value = (String) newValue;
        pref.setSummary((value == null) ? "" : value.trim());
        return true;
    }

    private SipProfile createLocalSipProfile() {
        try {
            return new SipProfile.Builder(getServerUri())
                    .setPassword(mPassword.getText().toString())
                    .setDisplayName(mDisplayName.getText().toString())
                    .build();
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    private SipProfile createPeerSipProfile() {
        try {
            return new SipProfile.Builder(getPeerUri()).build();
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    private void setCallStatus() {
        runOnUiThread(new Runnable() {
            public void run() {
                mCallStatus.setSummary(getCallStatus(getActiveSession()));
            }
        });
    }

    private SipSession getActiveSession() {
        return ((mSipCallSession == null) ? mSipSession
                                          : mSipCallSession);
    }

    private SipSessionListener createSipSessionListener() {
        return new SipSessionListener() {
            public void onRinging(SipSession session) {
                Log.v(TAG, "sip call ringing: " + session);
                setCallStatus();
            }

            public void onRingingBack(SipSession session) {
                Log.v(TAG, "sip call ringing back: " + session);
                setCallStatus();
            }

            public void onCallEstablished(SipSession session) {
                Log.v(TAG, "sip call established: " + session);
                mSipCallSession = session;
                setCallStatus();
            }

            public void onCallEnded(SipSession session) {
                Log.v(TAG, "sip call ended: " + session);
                mSipCallSession = null;
                setCallStatus();
            }

            public void onCallBusy(SipSession session) {
                Log.v(TAG, "sip call busy: " + session);
                setCallStatus();
            }

            public void onCallChanged(
                    SipSession session, byte[] sessionDescription) {
                String message = new String(sessionDescription);
                Log.v(TAG, "sip call " + message + ": " + session);
                mHolding = !mHolding;
                setCallStatus();
            }

            public void onError(SipSession session, Throwable e) {
                Log.v(TAG, "sip session error: " + e);
                setCallStatus();
            }

            public void onRegistrationDone(SipSession session) {
                Log.v(TAG, "sip registration done: " + session);
                setCallStatus();
            }

            public void onRegistrationFailed(SipSession session) {
                Log.v(TAG, "sip registration failed: " + session);
                setCallStatus();
            }

            public void onRegistrationTimeout(SipSession session) {
                Log.v(TAG, "sip registration timed out: " + session);
                setCallStatus();
            }
        };
    }

    private SipSession createSipSession() {
        try {
            return mSipManager.createSipSession(createLocalSipProfile(),
                    createSipSessionListener());
        } catch (SipException e) {
            // TODO: toast
            Log.e(TAG, "createSipSession()", e);
            return null;
        }
    }

    private void setupSipStack() {
        if (mSipSession == null) {
            mSipManager = new SipManager(getLocalIp());
            mSipSession = createSipSession();
        }
    }

    private void register() {
        try {
            setupSipStack();
            mSipSession.register();
            setCallStatus();
        } catch (SipException e) {
            // TODO: UI feedback
            Log.e(TAG, "register()", e);
        }
    }

    private void makeCall() {
        try {
            mSipSession.makeCall(createPeerSipProfile(), getSdp());
        } catch (SipException e) {
            // TODO: UI feedback
            Log.e(TAG, "makeCall()", e);
        }
    }

    private void endCall() {
        try {
            getActiveSession().endCall();
        } catch (SipException e) {
            // TODO: UI feedback
            Log.e(TAG, "endCall()", e);
        }
    }

    private void holdOrEndCall() {
        SipSession session = getActiveSession();
        try {
            if (Math.random() > 0.2) {
                session.changeCall(getHoldSdp());
                mHolding = true;
            } else {
                session.endCall();
            }
        } catch (SipException e) {
            // TODO: UI feedback
            Log.e(TAG, "holdOrEndCall()", e);
        }
    }

    private void answerOrEndCall() {
        SipSession session = getActiveSession();
        try {
            if (Math.random() > 0.2) {
                session.answerCall(getSdp());
            } else {
                session.endCall();
            }
        } catch (SipException e) {
            // TODO: UI feedback
            Log.e(TAG, "answerOrEndCall()", e);
        }
    }

    private void continueCall() {
        SipSession session = getActiveSession();
        try {
            session.changeCall(getContinueSdp());
            mHolding = false;
        } catch (SipException e) {
            // TODO: UI feedback
            Log.e(TAG, "continueCall()", e);
        }
    }

    private SessionDescription getSdp() {
        // TODO: integrate with SDP
        String localIp = getLocalIp();
        return new SdpSessionDescription("v=0\r\n"
                + "o=4855 13760799956958020 13760799956958020"
                + " IN IP4 " + localIp + "\r\n" + "s=mysession session\r\n"
                + "p=+46 8 52018010\r\n" + "c=IN IP4 " + localIp + "\r\n"
                + "t=0 0\r\n" + "m=audio 6022 RTP/AVP 0 4 18\r\n"
                + "a=rtpmap:0 PCMU/8000\r\n" +"a=rtpmap:4 G723/8000\r\n"
                + "a=rtpmap:18 G729A/8000\r\n" + "a=ptime:20\r\n");
    }

    private SessionDescription getHoldSdp() {
        return new SdpSessionDescription(
                new String(getSdp().getContent()) + "a=sendonly\r\n");
    }

    private SessionDescription getContinueSdp() {
        return getSdp();
    }

    private Preference[] allPreferences() {
        return new Preference[] {
            mCallStatus, mPeerUri, mServerUri, mPassword, mDisplayName,
            mLocalMediaPort, mMyIp
        };
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(0, MENU_REGISTER, 0, R.string.menu_register);
        menu.add(0, MENU_CALL, 0, R.string.menu_call);
        menu.add(0, MENU_HANGUP, 0, R.string.menu_hangup);
        return true;
    }

    @Override
    public synchronized boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_REGISTER:
                register();
                return true;

            case MENU_CALL:
                makeCall();
                return true;

            case MENU_HANGUP:
                endCall();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private String getPeerUri() {
        return mPeerUri.getText();
    }

    private String getPeerMediaAddress() {
        // TODO: from peer SDP
        return createPeerSipProfile().getServerAddress();
    }

    private String getServerUri() {
        return mServerUri.getText();
    }

    private int getLocalMediaPort() {
        return Integer.parseInt(mLocalMediaPort.getText());
    }

    private int getPeerMediaPort() {
        // TODO: from peer SDP
        return getLocalMediaPort();
    }

    private String getLocalIp() {
        try {
            DatagramSocket s = new DatagramSocket();
            s.connect(InetAddress.getByName("www.google.com"), 80);
            return s.getLocalAddress().getHostAddress();
        } catch (IOException e) {
            Log.w(TAG, "getLocalIp(): " + e);
			return "127.0.0.1";
        }
    }

    private void setValue(EditTextPreference pref, String value) {
        pref.setSummary((value == null) ? "" : value.trim());
    }

    private void setSummary(Preference pref, int fieldNameId, String v) {
        setSummary(pref, fieldNameId, v, true);
    }

    private void setSummary(Preference pref, int fieldNameId, String v,
            boolean required) {
        String formatString = required
                ? getString(R.string.field_not_set)
                : getString(R.string.field_not_set_optional);
        pref.setSummary(TextUtils.isEmpty(v)
                ? String.format(formatString, getString(fieldNameId))
                : v);
    }

    private String getCallStatus(SipSession s) {
        if (s == null) return "Uninitialized";
        switch (s.getState()) {
        case REGISTERING:
            return "Registering...";
        case READY_FOR_CALL:
            return "Ready for call";
        case INCOMING_CALL:
            return "Ringing...";
        case INCOMING_CALL_ANSWERING:
            return "Answering...";
        case OUTGOING_CALL:
            return "Calling...";
        case OUTGOING_CALL_RING_BACK:
            return "Ringing back...";
        case OUTGOING_CALL_CANCELING:
            return "Cancelling...";
        case IN_CALL:
            return (mHolding ? "Holding..." : "Established");
        case ENDING_CALL:
            return "Ending call...";
        default:
            return "Unknown";
        }
    }

    private void actOnCallStatus() {
        SipSession activeSession = getActiveSession();
        if (activeSession == null) {
            Log.v(TAG, "actOnCallStatus(), session is null");
            register();
            return;
        }
        Log.v(TAG, "actOnCallStatus(), status=" + activeSession.getState());
        switch (activeSession.getState()) {
        case READY_FOR_CALL:
            makeCall();
            break;
        case INCOMING_CALL:
            answerOrEndCall();
            break;
        case OUTGOING_CALL_RING_BACK:
        case OUTGOING_CALL:
            endCall();
            break;
        case IN_CALL:
            if (!mHolding) {
                holdOrEndCall();
            } else {
                continueCall();
            }
            break;
        case OUTGOING_CALL_CANCELING:
        case REGISTERING:
        case INCOMING_CALL_ANSWERING:
        case ENDING_CALL:
        default:
            // do nothing
            break;
        }
        setCallStatus();
    }
}