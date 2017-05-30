package com.fuse.StreamingPlayer;

import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.KeyEvent;

import java.util.ArrayList;

public final class StreamingAudioService
        extends Service
        implements MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener, MediaPlayer.OnCompletionListener, AudioManager.OnAudioFocusChangeListener
{

    public interface StreamingAudioClient
    {
        void OnStatusChanged();

        void OnHasPrevNextChanged();

        void OnCurrentTrackChanged();

        void OnInternalStatusChanged(int i);
    }

    public class LocalBinder extends Binder
    {
        public StreamingAudioService getService()
        {
            // Return this instance of LocalService so clients can call public methods
            return StreamingAudioService.this;
        }
    }

    MediaPlayer _player;
    MediaSessionCompat _session;

    LocalBinder _binder = new LocalBinder();
    boolean _prepared = false;

    StreamingAudioClient _streamingAudioClient;

    Track _currentTrack;
    ArrayList<Track> _playlist = new ArrayList<Track>();


    public void setAudioClient(StreamingAudioClient bgp)
    {
        _streamingAudioClient = bgp;
    }

    @Override
    public void onCreate()
    {
        Logger.Log("=======================  Android: Created new MediaPlayer ===============================");
        _player = new MediaPlayer();
        _player.setOnErrorListener(this);
        _player.setOnPreparedListener(this);
        _player.setOnCompletionListener(this);

        // Todo: somewhere we need to request audioFocus. See 8:35 in video
        //       this also plays into when we need to

        try
        {
            initMediaSessions();
            startNoisyReceiver();
        }
        catch (RemoteException e)
        {
            com.fuse.AndroidInteropHelper.UncheckedThrow(e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.abandonAudioFocus(this);
        stopNoisyReciever();
        _session.release();
        NotificationManagerCompat.from(this).cancel(1);
    }

    private void initMediaSessions() throws RemoteException
    {
        _session = new MediaSessionCompat(getApplicationContext(), "FuseStreamingPlayerSession");
        _session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        _session.setActive(true);

        _session.setCallback(new MediaSessionCompat.Callback()
         {
             @Override
             public boolean onMediaButtonEvent(Intent mediaButtonEvent)
             {
                 return super.onMediaButtonEvent(mediaButtonEvent);
             }

             @Override
             public void onPlay()
             {
                 super.onPlay();
                 if( !successfullyRetrievedAudioFocus() )
                     return;
                 Resume();
             }

             @Override
             public void onPause()
             {
                 super.onPause();
                 Pause();
             }

             @Override
             public void onSkipToNext()
             {
                 super.onSkipToNext();
                 Logger.Log("Skipping from media notification: " + _currentTrack.Name);
                 Next();
             }

             @Override
             public void onSkipToPrevious()
             {
                 super.onSkipToPrevious();
                 Previous();
             }

             @Override
             public void onStop()
             {
                 super.onStop();
                 Stop();
                 NotificationManager notificationManager = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
                 notificationManager.cancel(1);
                 Intent intent = new Intent(getApplicationContext(), StreamingAudioService.class);
                 stopService(intent);
             }

             @Override
             public void onSeekTo(long pos)
             {
                 super.onSeekTo(pos);
                 Seek((int)pos);
             }
         });
    }

    private boolean successfullyRetrievedAudioFocus() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        int result = audioManager.requestAudioFocus(this,
                AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);

        return result == AudioManager.AUDIOFOCUS_GAIN;
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch( focusChange ) {
            case AudioManager.AUDIOFOCUS_LOSS: {
                if( _player.isPlaying() ) {
                    Stop();
                }
                break;
            }
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT: {
                Pause();
                break;
            }
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: {
                if( _player != null ) {
                    _player.setVolume(0.3f, 0.3f);
                }
                break;
            }
            case AudioManager.AUDIOFOCUS_GAIN: {
                if( _player != null ) {
                    if( !_player.isPlaying() ) {
                        Resume();
                    }
                    _player.setVolume(1.0f, 1.0f);
                }
                break;
            }
        }
    }

    private BroadcastReceiver _noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if( _player != null && _player.isPlaying())
                _player.pause();
        }
    };

    private void startNoisyReceiver() {
        //Handles headphones coming unplugged. cannot be done through a manifest receiver
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(_noisyReceiver, filter);
    }

    private void stopNoisyReciever()
    {
        unregisterReceiver(_noisyReceiver);
    }

    @Override
    public IBinder onBind(Intent intent)
    {
        return _binder;
    }

    AndroidPlayerState _state = AndroidPlayerState.Idle;

    void setState(AndroidPlayerState state)
    {
        _state = state;
        int intState = 0;
        switch (state)
        {
            case Idle:
                intState = 0;
                Logger.Log("AndroidStatus: Idle");
                break;
            case Initialized:
                intState = 1;
                Logger.Log("AndroidStatus: Initialized");
                break;
            case Preparing:
                intState = 2;
                Logger.Log("AndroidStatus: Preparing");
                break;
            case Prepared:
                intState = 3;
                Logger.Log("AndroidStatus: Prepared");
                break;
            case Started:
                intState = 4;
                Logger.Log("AndroidStatus: Started");
                break;
            case Stopped:
                intState = 5;
                Logger.Log("AndroidStatus: Stopped");
                break;
            case Paused:
                intState = 6;
                Logger.Log("AndroidStatus: Paused");
                break;
            case PlaybackCompleted:
                intState = 7;
                Logger.Log("AndroidStatus: PlaybackCompleted");
                break;
            case Error:
                intState = 8;
                Logger.Log("AndroidStatus: Error");
                break;
            case End:
                intState = 9;
                Logger.Log("AndroidStatus: End");
                break;
        }
        _streamingAudioClient.OnInternalStatusChanged(intState);
    }

    @Override
    public void onPrepared(MediaPlayer mp)
    {
        _prepared = true;
        setState(AndroidPlayerState.Prepared);
        _player.setLooping(false);
        _player.start();

        _session.setPlaybackState(new PlaybackStateCompat.Builder()
                                  .setActions(PlaybackStateCompat.ACTION_PLAY |
                                              PlaybackStateCompat.ACTION_PLAY_PAUSE |
                                              PlaybackStateCompat.ACTION_PAUSE |
                                              PlaybackStateCompat.ACTION_STOP |
                                              PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                                              PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                                              PlaybackStateCompat.ACTION_SEEK_TO |
                                              PlaybackStateCompat.ACTION_PLAY_FROM_URI)
                                  .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
                                  .build());
        setState(AndroidPlayerState.Started);
        buildNotification(android.R.drawable.ic_media_pause, "Pause", KeyEvent.KEYCODE_MEDIA_PAUSE);
    }

    public void Play(Track track)
    {
        if (_prepared)
        {
            _prepared = false;
            _player.stop();
            _player.reset();
        }
        try
        {
            _player.reset();
            _state = AndroidPlayerState.Initialized;
            Logger.Log("SetDataSource: state: " + _state);
            _player.setDataSource(track.Url);
            setState(AndroidPlayerState.Preparing);
            _player.prepareAsync();
        }
        catch (Exception e)
        {
            Logger.Log("Exception while setting MediaPlayer DataSource");
        }

        _currentTrack = track;
        if (_streamingAudioClient != null)
        {
            _streamingAudioClient.OnCurrentTrackChanged();
            _streamingAudioClient.OnHasPrevNextChanged();
        }
    }

    public void Resume()
    {
        if (_prepared)
        {
            if (_player.isPlaying())
            {
                _player.seekTo(0);
            }
            else if (_prepared)
            {
                _player.start();
                setState(AndroidPlayerState.Started);
            }
            buildNotification(android.R.drawable.ic_media_pause, "Pause", KeyEvent.KEYCODE_MEDIA_PAUSE);
        }
    }

    public int CurrentTrackIndex()
    {
        return _playlist.indexOf(_currentTrack);
    }

    public double GetCurrentPosition()
    {
        if (_prepared)
        {
            return _player.getCurrentPosition();
        }
        return 0.0;
    }

    public double GetCurrentTrackDuration()
    {
        if (_prepared)
        {
            return _player.getDuration();
        }
        return 0.0;
    }

    public Track GetCurrentTrack()
    {
        return _currentTrack;
    }

    public void Seek(int milliseconds)
    {
        if (_prepared)
        {
            _player.seekTo(milliseconds);
        }
    }

    public void SetPlaylist(Track[] tracks)
    {
        _playlist.clear();
        for (int i = 0; i < tracks.length; i++)
        {
            _playlist.add(tracks[i]);
        }
        _streamingAudioClient.OnHasPrevNextChanged();
    }

    public void AddTrack(Track track)
    {
        _playlist.add(track);
    }

    public void Next()
    {
        if (HasNext())
        {
            Play(_playlist.get(CurrentTrackIndex() + 1));
        }
        _streamingAudioClient.OnHasPrevNextChanged();
    }

    public void Previous()
    {
        if (HasPrevious())
        {
            Play(_playlist.get(CurrentTrackIndex() - 1));
        }
        _streamingAudioClient.OnHasPrevNextChanged();
    }

    public boolean HasNext()
    {
        int currentIndex = CurrentTrackIndex();
        int playlistSize = _playlist.size();
        return currentIndex > -1 && currentIndex < playlistSize - 1;
    }

    public boolean HasPrevious()
    {
        int currentIndex = CurrentTrackIndex();
        return currentIndex > 0;
    }

    public void Pause()
    {
        if (_state == AndroidPlayerState.Started)
        {
            _player.pause();
            setState(AndroidPlayerState.Paused);

            buildNotification(android.R.drawable.ic_media_play, "Play", KeyEvent.KEYCODE_MEDIA_PLAY);
        }
    }

    public void Stop()
    {
        _prepared = false;
        _player.stop();
        _player.reset();
        setState(AndroidPlayerState.Idle);
        buildNotification(android.R.drawable.ic_media_play, "Play", KeyEvent.KEYCODE_MEDIA_PLAY);
    }

    @Override
    public void onCompletion(MediaPlayer mp)
    {
        Logger.Log("Android track completed");
        Next();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra)
    {
        Logger.Log("Error while mediaplayer in state: " + _state);
        Logger.Log("We did get an error: what:" + what + ", extra:" + extra);
        return true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        MediaButtonReceiver.handleIntent(_session, intent);
        return super.onStartCommand(intent, flags, startId);
    }

    private void buildNotification(int primaryActionIcon, String primaryActionTitle, int primaryActionKeyEvent)
    {
        ArtworkMediaNotification.Notify(_currentTrack, _session, this, primaryActionIcon, primaryActionTitle, primaryActionKeyEvent);
    }

    @Override
    public boolean onUnbind(Intent intent)
    {
        _session.release();
        return super.onUnbind(intent);
    }
}
