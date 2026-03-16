package ee.forgr.audio;

public class Constant {

    public static final String ERROR_AUDIO_ID_MISSING = "Audio Id is missing";
    public static final String ERROR_AUDIO_ASSET_MISSING = "Audio Asset is missing";
    public static final String ERROR_AUDIO_EXISTS = "Audio Asset already exists";
    public static final String ERROR_ASSET_PATH_MISSING = "Asset Path is missing";
    public static final String ERROR_ASSET_NOT_LOADED = "Asset is not loaded";

    public static final String ASSET_ID = "assetId";
    public static final String ASSET_PATH = "assetPath";
    public static final String OPT_FOCUS_AUDIO = "focus";
    public static final String TIME = "time";
    public static final String DELAY = "delay";
    public static final String VOLUME = "volume";
    public static final String RATE = "rate";
    public static final String DURATION = "duration";
    public static final String AUDIO_CHANNEL_NUM = "audioChannelNum";
    public static final String LOOP = "loop";
    public static final String PLAY = "play";
    public static final String FADE_IN = "fadeIn";
    public static final String FADE_OUT = "fadeOut";
    public static final String FADE_IN_DURATION = "fadeInDuration";
    public static final String FADE_OUT_DURATION = "fadeOutDuration";
    public static final String FADE_OUT_START_TIME = "fadeOutStartTime";
    public static final String SHOW_NOTIFICATION = "showNotification";
    public static final String NOTIFICATION_METADATA = "notificationMetadata";
    public static final int INVALID = 0;
    public static final int PREPARED = 1;
    public static final int PENDING_PLAY = 2;
    public static final int PLAYING = 3;
    public static final int PENDING_LOOP = 4;
    public static final int LOOPING = 5;
    public static final int PAUSE = 6;
}
