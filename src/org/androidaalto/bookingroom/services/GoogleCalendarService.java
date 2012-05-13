/**
   Copyright: 2011 Android Aalto

   This file is part of BookingRoom.

   BookingRoom is free software; you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation; either version 3 of the License, or
   (at your option) any later version.

   BookingRoom is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.

   You should have received a copy of the GNU General Public License
   along with BookingRoom. If not, see <http://www.gnu.org/licenses/>.
 */

package org.androidaalto.bookingroom.services;

import com.google.api.client.googleapis.auth.oauth2.draft10.GoogleAccessProtectedResource;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpContent;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.GenericJson;
import com.google.api.client.json.JsonParser;
import com.google.api.client.json.jackson.JacksonFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.Calendar.Events.List;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.Events;

import org.androidaalto.bookingroom.R;
import org.androidaalto.bookingroom.logic.MeetingManager;
import org.androidaalto.bookingroom.util.DateUtils;

import android.content.Context;
import android.text.format.Time;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class GoogleCalendarService {
    private static final String REFRESH_TOKEN_URL = "https://accounts.google.com/o/oauth2/token";
    private static String LOG_TAG = GoogleCalendarService.class.getCanonicalName();
    private static Context context;
    private static ExtendedRunnable runnable;
    private static final int REFRESH_INTERVAL = 15 * 1000;

    private static class ExtendedRunnable implements Runnable {
        private boolean running;

        @Override
        public void run() {
            running = true;
            Thread.currentThread().setName("GoogleCalendarService.Runnable");
            while (running) {
                try {
                    fetchEvents();
                    Thread.sleep(REFRESH_INTERVAL);
                } catch (IOException e) {
                    Log.e(LOG_TAG, "Unable to fetch google calendar data", e);
                } catch (InterruptedException e) {
                }
            }
        }

        public boolean isRunning() {
            return running;
        }

        public void setRunning(boolean running) {
            this.running = running;
        }
    }

    public static void start(Context context) {
        Log.d(LOG_TAG, "GoogleCalendarService.start()");
        if (GoogleCalendarService.runnable != null && GoogleCalendarService.runnable.isRunning()) {
            Log.w(LOG_TAG, "GoogleCalendarService already running");
            return;
        }
        if (context == null) {
            Log.e(LOG_TAG, "Context can't be null!! Calendar events won't be fetched!");
            return;
        }
        GoogleCalendarService.context = context;
        GoogleCalendarService.runnable = new ExtendedRunnable();
        new Thread(GoogleCalendarService.runnable).start();
    }

    public static void fetchEvents() throws IOException {
        Time firstDayOfThisWeek = DateUtils.getFirstDayOfThisWeek();

        HttpTransport httpTransport = new NetHttpTransport();
        JacksonFactory jsonFactory = new JacksonFactory();

        // The clientId and clientSecret are copied from the API Access tab on
        // the Google APIs Console
        String clientId = GoogleCalendarService.context.getString(R.string.client_id);
        String clientSecret = GoogleCalendarService.context.getString(R.string.client_secret);
        String refreshToken = GoogleCalendarService.context.getString(R.string.refresh_token);

        String accessToken = refreshToken(httpTransport, clientId, clientSecret, refreshToken);

        GoogleAccessProtectedResource accessProtectedResource = new GoogleAccessProtectedResource(
                accessToken, httpTransport, jsonFactory, clientId, clientSecret,
                refreshToken);

        Calendar service = Calendar.builder(httpTransport, jsonFactory)
                .setApplicationName("BOOKING_ROOM")
                .setHttpRequestInitializer(accessProtectedResource)
                .build();

        String boardRoomId = GoogleCalendarService.context
                .getString(R.string.board_room_calendar_id);
        List listEvents = service.events().list(boardRoomId);

        Time now = new Time();
        now.setToNow();
        String min = now.format3339(false);
        listEvents.setTimeMin(min);
        Time maxTime = new Time(now);
        maxTime.monthDay += 5;
        maxTime.normalize(true);
        String max = maxTime.format3339(false);
        listEvents.setTimeMax(max);
        Log.d(LOG_TAG,
                "Fetching from : " + listEvents.getTimeMin() + " to " + listEvents.getTimeMax());
        Events events = listEvents.execute();

        // Remove all the current entries
        MeetingManager.clean();

        while (true) {
            for (Event event : events.getItems()) {
                Log.d(LOG_TAG, event.toPrettyString());
                Time start = new Time();
                start.parse3339(event.getStart().getDateTime().toStringRfc3339());
                Time end = new Time();
                end.parse3339(event.getEnd().getDateTime().toStringRfc3339());
                if (end.before(firstDayOfThisWeek))
                    continue;
                start.normalize(true);
                MeetingManager.storeEvent(start, end, event.getSummary(), "fake", "fake@fake.com");
            }
            String pageToken = events.getNextPageToken();
            if (pageToken == null || pageToken.length() == 0)
                break;
            events = service.events().list(boardRoomId).setTimeMin(min).setTimeMax(max)
                    .setPageToken(pageToken).execute();
        }
    }

    /**
     * @param httpTransport
     * @return
     * @throws IOException
     */
    private static String refreshToken(HttpTransport httpTransport, String clientId,
            String clientSecret,
            String refreshToken) throws IOException {
        HttpRequestFactory reqFactory = httpTransport.createRequestFactory();
        StringBuilder sb = new StringBuilder();
        sb.append("client_id=");
        sb.append(clientId);
        sb.append("&client_secret=");
        sb.append(clientSecret);
        sb.append("&refresh_token=");
        sb.append(refreshToken);
        sb.append("&grant_type=refresh_token");
        final byte[] refreshContent = sb.toString().getBytes();
        HttpContent content = // new UrlEncodedContent(refreshContent);
        new HttpContent() {

            @Override
            public void writeTo(OutputStream out) throws IOException {
                out.write(refreshContent);
            }

            @Override
            public boolean retrySupported() {
                return true;
            }

            @Override
            public String getType() {
                return "application/x-www-form-urlencoded";
            }

            @Override
            public long getLength() throws IOException {
                return refreshContent.length;
            }

            @Override
            public String getEncoding() {
                // TODO Auto-generated method stub
                return null;
            }
        };
        try {
            HttpRequest req = reqFactory.buildPostRequest(new GenericUrl(REFRESH_TOKEN_URL),
                    content);
            HttpResponse resp = req.execute();
            InputStream respContentIS = resp.getContent();
            JacksonFactory fac = new JacksonFactory();
            JsonParser jparser = fac.createJsonParser(respContentIS);
            GenericJson jsonContent = jparser.parse(GenericJson.class, null);
            return (String) jsonContent.get("access_token");
        } catch (IOException e) {
            throw e;
        }
    }

    private static void setRunning(boolean running) {
        GoogleCalendarService.runnable.setRunning(false);
    }

    /**
     * 
     */
    public static void stop() {
        setRunning(false);
    }
}
