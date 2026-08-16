package org.unrwa.yarmoukfield;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** PIN gate with salted PBKDF2 hashing, retry throttling, and a three-minute background lock. */
public final class AppSecurity {
    public static final long LOCK_DELAY_MS=3*60*1000L;
    private static final String PREFS="app_security";
    private static final int ITERATIONS=150_000,KEY_BITS=256,MAX_ATTEMPTS=5;
    private static final long LOCKOUT_MS=30_000L;
    private final SharedPreferences preferences;

    public static final class Verification {
        public final boolean success,locked;
        public final int attemptsLeft;
        public final long retryAfterMs;
        Verification(boolean success,boolean locked,int attemptsLeft,long retryAfterMs){this.success=success;this.locked=locked;this.attemptsLeft=attemptsLeft;this.retryAfterMs=retryAfterMs;}
    }

    public AppSecurity(Context context){preferences=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    public boolean isConfigured(){return !preferences.getString("pin_hash","").isEmpty()&&!preferences.getString("pin_salt","").isEmpty();}
    public static boolean isValidPin(String pin){return pin!=null&&pin.matches("\\d{6}");}

    public void setPin(String pin)throws Exception{
        if(!isValidPin(pin))throw new IllegalArgumentException("يجب أن يتكون كود الحماية من 6 أرقام");
        byte[]salt=new byte[16];new SecureRandom().nextBytes(salt);String algorithm=preferredAlgorithm();byte[]hash=derive(pin,salt,algorithm);
        preferences.edit().putString("pin_salt",Base64.encodeToString(salt,Base64.NO_WRAP)).putString("pin_hash",Base64.encodeToString(hash,Base64.NO_WRAP)).putString("pin_algorithm",algorithm).putInt("failed_attempts",0).putLong("lockout_until",0).apply();
    }

    public Verification verify(String pin){
        long now=System.currentTimeMillis(),until=preferences.getLong("lockout_until",0);if(until>now)return new Verification(false,true,0,until-now);
        try{byte[]salt=Base64.decode(preferences.getString("pin_salt",""),Base64.NO_WRAP);byte[]expected=Base64.decode(preferences.getString("pin_hash",""),Base64.NO_WRAP);String algorithm=preferences.getString("pin_algorithm","PBKDF2WithHmacSHA1");byte[]actual=derive(pin==null?"":pin,salt,algorithm);if(MessageDigest.isEqual(expected,actual)){preferences.edit().putInt("failed_attempts",0).putLong("lockout_until",0).apply();return new Verification(true,false,MAX_ATTEMPTS,0);}}
        catch(Exception ignored){}
        int failures=preferences.getInt("failed_attempts",0)+1;if(failures>=MAX_ATTEMPTS){preferences.edit().putInt("failed_attempts",0).putLong("lockout_until",now+LOCKOUT_MS).apply();return new Verification(false,true,0,LOCKOUT_MS);}preferences.edit().putInt("failed_attempts",failures).apply();return new Verification(false,false,MAX_ATTEMPTS-failures,0);
    }

    public void markBackground(){preferences.edit().putLong("background_at",System.currentTimeMillis()).apply();}
    public boolean shouldRelock(){long backgroundAt=preferences.getLong("background_at",0);return backgroundAt>0&&System.currentTimeMillis()-backgroundAt>=LOCK_DELAY_MS;}
    public void clearBackground(){preferences.edit().putLong("background_at",0).apply();}

    private static String preferredAlgorithm(){try{SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");return "PBKDF2WithHmacSHA256";}catch(Exception ignored){return "PBKDF2WithHmacSHA1";}}
    private static byte[]derive(String pin,byte[]salt,String algorithm)throws Exception{PBEKeySpec spec=new PBEKeySpec(pin.toCharArray(),salt,ITERATIONS,KEY_BITS);try{return SecretKeyFactory.getInstance(algorithm).generateSecret(spec).getEncoded();}finally{spec.clearPassword();}}
}
