package com.mongodb.shortnr;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.TargetApi;
import android.content.pm.PackageManager;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.app.LoaderManager.LoaderCallbacks;

import android.content.CursorLoader;
import android.content.Loader;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;

import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;

import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInResult;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;

import com.mongodb.stitch.android.core.auth.StitchUser;
import com.mongodb.stitch.android.services.mongodb.remote.RemoteFindIterable;
import com.mongodb.stitch.android.services.mongodb.remote.RemoteMongoClient;
import com.mongodb.stitch.android.services.mongodb.remote.RemoteMongoCollection;
import com.mongodb.stitch.android.core.Stitch;
import com.mongodb.stitch.android.core.auth.StitchAuth;
import com.mongodb.stitch.android.core.auth.StitchAuthListener;
import com.mongodb.stitch.android.core.StitchAppClient;
import com.mongodb.stitch.core.auth.providers.anonymous.AnonymousCredential;
import com.mongodb.stitch.core.auth.providers.facebook.FacebookCredential;
import com.mongodb.stitch.core.auth.providers.google.GoogleCredential;
import com.mongodb.stitch.core.services.mongodb.remote.RemoteInsertOneResult;

import org.bson.Document;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.google.android.gms.auth.api.Auth.GOOGLE_SIGN_IN_API;
import static com.google.android.gms.auth.api.Auth.GoogleSignInApi;


/**
 * URL shortener with google login support
 */
public class MainActivity extends AppCompatActivity {


    private static final String TAG = "Shortnr";
    private static final int RC_SIGN_IN = 421;

    //private CallbackManager _callbackManager;
    private GoogleApiClient _googleApiClient;
    private StitchAppClient _client;
    private RemoteMongoClient _mongoClient;

    private Handler _handler;
    private Runnable _refresher;

    // UI references.
    private AutoCompleteTextView mEmailView;
    private EditText mPasswordView;
    private View mProgressView;
    private View mLoginFormView;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        _handler = new Handler();
        //_refresher = new ListRefresher(this);

        this._client = Stitch.getDefaultAppClient();
        this._client.getAuth().addAuthListener(new MyAuthListener(this));

        _mongoClient = this._client.getServiceClient(RemoteMongoClient.factory, "mongodb-atlas");
        setupLogin();
    }

    private static class MyAuthListener implements StitchAuthListener {

        private WeakReference<MainActivity> _main;
        private StitchUser _user;

        public MyAuthListener(final MainActivity activity) {
            _main = new WeakReference<>(activity);
        }

        @Override
        public void onAuthEvent(final StitchAuth auth) {
            if (auth.isLoggedIn() && _user == null) {
                Log.d(TAG, "Logged into Stitch");
                _user = auth.getUser();
                return;
            }

            if (!auth.isLoggedIn() && _user != null) {
                _user = null;
                onLogout();
            }
        }

        public void onLogout() {

            final MainActivity activity = _main.get();

            final List<Task<Void>> futures = new ArrayList<>();
            if (activity != null) {
                activity._handler.removeCallbacks(activity._refresher);

                if (activity._googleApiClient != null) {
                    final TaskCompletionSource<Void> future = new TaskCompletionSource<>();
                    GoogleSignInApi.signOut(
                            activity._googleApiClient).setResultCallback(new ResultCallback<Status>() {
                        @Override
                        public void onResult(@NonNull final Status ignored) {
                            future.setResult(null);
                        }
                    });
                    futures.add(future.getTask());
                }

                Tasks.whenAll(futures).addOnCompleteListener(new OnCompleteListener<Void>() {
                    @Override
                    public void onComplete(@NonNull final Task<Void> ignored) {
                        activity.setupLogin();
                    }
                });
            }
        }
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == RC_SIGN_IN) {
            final GoogleSignInResult result = GoogleSignInApi.getSignInResultFromIntent(data);
            handleGooglSignInResult(result);
            return;
        }

        /*
        if (_callbackManager != null) {
            _callbackManager.onActivityResult(requestCode, resultCode, data);
            return;
        }
        */

        Log.e(TAG, "Nowhere to send activity result for ourselves");
    }

    private void handleGooglSignInResult(final GoogleSignInResult result) {
        if (result == null) {
            Log.e(TAG, "Got a null GoogleSignInResult");
            return;
        }

        Log.d(TAG, "handleGooglSignInResult:" + result.isSuccess());
        if (result.isSuccess()) {
            final GoogleCredential googleCredential = new GoogleCredential(result.getSignInAccount().getServerAuthCode());
            _client.getAuth().loginWithCredential(googleCredential).addOnCompleteListener(new OnCompleteListener<StitchUser>() {
                @Override
                public void onComplete(@NonNull final Task<StitchUser> task) {
                    if (task.isSuccessful()) {
                        initMainView();
                    } else {
                        Log.e(TAG, "Error logging in with Google", task.getException());
                    }
                }
            });
        }
    }


    /**
     * Shows the progress UI and hides the login form.
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB_MR2)
    private void showProgress(final boolean show) {
        // On Honeycomb MR2 we have the ViewPropertyAnimator APIs, which allow
        // for very easy animations. If available, use these APIs to fade-in
        // the progress spinner.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR2) {
            int shortAnimTime = getResources().getInteger(android.R.integer.config_shortAnimTime);

            mLoginFormView.setVisibility(show ? View.GONE : View.VISIBLE);
            mLoginFormView.animate().setDuration(shortAnimTime).alpha(
                    show ? 0 : 1).setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mLoginFormView.setVisibility(show ? View.GONE : View.VISIBLE);
                }
            });

            mProgressView.setVisibility(show ? View.VISIBLE : View.GONE);
            mProgressView.animate().setDuration(shortAnimTime).alpha(
                    show ? 1 : 0).setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mProgressView.setVisibility(show ? View.VISIBLE : View.GONE);
                }
            });
        } else {
            // The ViewPropertyAnimator APIs are not available, so simply show
            // and hide the relevant UI components.
            mProgressView.setVisibility(show ? View.VISIBLE : View.GONE);
            mLoginFormView.setVisibility(show ? View.GONE : View.VISIBLE);
        }
    }

    private void initMainView() {
        setContentView(R.layout.activity_main);

        _refresher.run();
    }

    private void shortenURL(final String longURL) {
        final String shortURL = new String();

        // shorten the URL, save the redirection map in Atlas
        // Present the short URL to the user
    }

    private void setupLogin() {
        if (_client.getAuth().isLoggedIn()) {
            initMainView();
            return;
        }

        final String googleClientId = getString(R.string.google_client_id);

        setContentView(R.layout.activity_main);

        // If there is a valid Google Client ID defined in strings.xml, offer Google as a login option.
        final GoogleSignInOptions.Builder gsoBuilder = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestServerAuthCode(googleClientId, false);
        final GoogleSignInOptions gso = gsoBuilder.build();

        if (_googleApiClient != null) {
            _googleApiClient.stopAutoManage(MainActivity.this);
            _googleApiClient.disconnect();
        }

        _googleApiClient = new GoogleApiClient.Builder(MainActivity.this)
            .enableAutoManage(MainActivity.this, new GoogleApiClient.OnConnectionFailedListener() {
            @Override
            public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
                Log.e(TAG, "Error connecting to google: " + connectionResult.getErrorMessage());
            }
        })
        .addApi(GOOGLE_SIGN_IN_API, gso)
        .build();

        findViewById(R.id.google_login_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View ignored) {
                final Intent signInIntent =
                    GoogleSignInApi.getSignInIntent(_googleApiClient);
                startActivityForResult(signInIntent, RC_SIGN_IN);
            }
        });
        findViewById(R.id.google_login_button).setVisibility(View.VISIBLE);
    } 
}

