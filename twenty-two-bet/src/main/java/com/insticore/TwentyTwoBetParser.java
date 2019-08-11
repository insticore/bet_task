package com.insticore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.asynchttpclient.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class TwentyTwoBetParser {

    private final Logger logger = LoggerFactory.getLogger(TwentyTwoBetParser.class);
    private AsyncHttpClient asyncHttpClient;
    private static ExecutorService executorService;
    private static Lock tournamentLock = new ReentrantLock();

    public TwentyTwoBetParser() {
        asyncHttpClient = Dsl.asyncHttpClient(Dsl.config().setUserAgent("Mozilla/5.0"));
        executorService = Executors.newCachedThreadPool();
    }

    public void parseToConsole() {
        loadSports();
    }


    private void loadSports() {
        //Load page where sports links can be retrieved
        JsonNode prematchMenu = getJson("https://nodejs08.tglab.io/cache/20/en/en/Europe%2FPrague/prematch-menu.json");
        if (prematchMenu == null) {
            logger.error("Json wasn't received from nodejs08.tglab.io");
            return;
        }
        Iterator<JsonNode> sports = prematchMenu.get("data").get("sports").elements();

        //Map sports ids to their titles
        Map<Integer, String> sportsMap = new HashMap<>();
        while (sports.hasNext()) {
            JsonNode sport = sports.next();
            sportsMap.put(sport.get("id").asInt(), sport.get("title").asText());

        }

        //Some sports preselected -> can be done as input data
        final Integer[] DESIRED_SPORTS = {1, 2, 3, 4, 6, 12};
        List<Integer> desiredSportsList = Arrays.asList(DESIRED_SPORTS);
        Iterator<JsonNode> sportsMenu = prematchMenu.get("sports_menu").elements();

        while (sportsMenu.hasNext()) {
            JsonNode sportsMenuItem = sportsMenu.next();

            //For each sport id, which is in the desired list read the countries which have leagues to read
            Integer sportId = sportsMenuItem.get("id").asInt();
            if (desiredSportsList.contains(sportId)) {
                String sportName = sportsMap.get(sportId);
                System.out.println(sportName); // Print sport
                JsonNode countries = sportsMenuItem.get("countries");
                Iterator<JsonNode> countriesIterator = countries.elements();
                CountDownLatch countDownLatch = new CountDownLatch(countries.size());
                while (countriesIterator.hasNext()) {
                    JsonNode country = countriesIterator.next();

                    //For each country retrieve the tournaments
                    Iterator<JsonNode> tournaments = country.get("tournaments").elements();
                    while (tournaments.hasNext()) {
                        JsonNode tournament = tournaments.next();
                        String tournamentId = tournament.asText();
                        loadTournamentAsync(tournamentId, countDownLatch);
                    }
                }
                try {
                    //Wait until all async tournaments loading tasks are completed
                    countDownLatch.await();
                } catch (InterruptedException e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
        try {
            asyncHttpClient.close();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
    }

    /**
     * Read json with blocking of the calling thread
     * @param url
     * @return
     */
    private JsonNode getJson(String url) {
        BoundRequestBuilder getRequest = asyncHttpClient.prepareGet(url);
        Future<Response> responseFuture = getRequest.execute();
        try {
            Response response = responseFuture.get();
            String json = response.getResponseBody();
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.readTree(json);
        } catch (Exception e) {
            logger.error("JSON was not loaded from " + url, e);
        }
        return null;
    }

    /**
     * Reads all events for the tournament with async tasks
     * @param tournamentId
     * @param countDownLatch
     */
    private void loadTournamentAsync(String tournamentId, CountDownLatch countDownLatch) {
        String url = String.format("https://nodejs08.tglab.io/cache/20/en/en/%s/prematch-by-tournaments.json", tournamentId);
        Request request = asyncHttpClient.prepareGet(url).build();
        ListenableFuture<Response> listenableFuture = asyncHttpClient
                .executeRequest(request);
        listenableFuture.addListener(() -> {
            try {
                Response response = listenableFuture.get();

                //Make sure tournament printing is atomic
                tournamentLock.lock();
                loadTournament(response);
                tournamentLock.unlock();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                countDownLatch.countDown();
            }
        }, executorService);
    }

    /**
     * Loads all events for the tournament page in the response
     * @param response
     * @throws IOException
     */
    private void loadTournament(Response response) throws IOException {
        String tournamentsJson = response.getResponseBody();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode tournamentInfo = objectMapper.readTree(tournamentsJson);
        JsonNode eventsNode = tournamentInfo.get("events");
        Iterator<JsonNode> events = eventsNode.elements();

        String tournamentName = tournamentInfo.get("events").get(0).get("tournament_name").get("en").asText();

        // "Winner"-like tournaments are not necessary and should be omitted
        if (tournamentName.matches(".*Winner.*")) {
            return;
        }

        System.out.println(tournamentName);  //Print tournament

        while (events.hasNext()) {
            JsonNode event = events.next();
            String eventId = event.get("id").asText();
            loadEventSync(eventId);
        }
    }

    /**
     * Loads the event odds
     * @param eventId
     */
    private void loadEventSync(String eventId) {
        String eventInfoUrl = String.format("https://nodejs08.tglab.io/cache/20/en/en/%s/single-pre-event.json", eventId);
        JsonNode eventInfo = getJson(eventInfoUrl);
        if (eventInfo == null) {
            logger.error("No event json got from " + eventInfoUrl);
            return;
        }
        JsonNode info = eventInfo.get("info");
        String startDate = info.get("date_start").asText();
        String date = formatDateForOutput(startDate);

        Iterator<JsonNode> teams = info.get("teams").elements();
        String eventName = teams.next().asText();
        while (teams.hasNext()) {
            JsonNode team = teams.next();
            String nextTeamName = team.textValue();
            //When there are two teams a name is built like "<team A> vs <team B> vs ..."
            if (nextTeamName != null && !nextTeamName.isEmpty()) {
                eventName += " vs. " + nextTeamName;
            }
        }

        System.out.println(String.join(", ", eventName, date, eventId)); //Print event

        Iterator<JsonNode> odds = eventInfo.get("odds").elements();

        String sportId = info.get("sport_id").asText();

        //Split odds into appropriate markets by filter id
        Map<Integer, List<Odd>> oddsMap = new HashMap<>();
        while (odds.hasNext()) {
            JsonNode odd = odds.next();
            int filterId = odd.get("filter_id").asInt();
            int id = odd.get("id").asInt();
            String name = odd.get("team_name").get("en").asText();
            double value = odd.get("odd_value").asDouble();
            Odd oddObject = new Odd(id, name, value);
            if (oddsMap.containsKey(filterId)) {
                oddsMap.get(filterId).add(oddObject);
            } else {
                List<Odd> moreOdds = new ArrayList<>();
                moreOdds.add(oddObject);
                oddsMap.put(filterId, moreOdds);
            }
        }

        try {
            //Read all odds
            //Use the filter id to find the name of market for each odd in the market names map
            Map<Integer, String> oddNamesMap = getMarketNames(sportId);
            for (HashMap.Entry<Integer, List<Odd>> marketEntry : oddsMap.entrySet()) {
                int filterId = marketEntry.getKey();
                String marketName = oddNamesMap.get(filterId);
                System.out.println(marketName);  //Print market
                for (Odd odd : marketEntry.getValue()) {
                    //Print odd
                    System.out.println(String.format("\t\t%s, %.2f, %d", odd.getName(), odd.getValue(), odd.getId()));
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }


    }

    private String formatDateForOutput(String date) {
        SimpleDateFormat inFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.ENGLISH);
        Calendar calendar = new GregorianCalendar();
        try {
            calendar.setTime(inFormat.parse(date));
        } catch (ParseException e) {
            logger.error("Date could not be parsed", e);
        }
        SimpleDateFormat outFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm");
        return outFormat.format(calendar.getTime());
    }

    /**
     * Load the market names map for the given sport
     * @param sportId
     * @return
     * @throws IOException
     */
    private Map<Integer, String> getMarketNames(String sportId) throws IOException {
        String oddFiltersUrl = String.format("https://nodejs.tglab.io/cache/%s/0/en/20/odd-filters.json", sportId);
        JsonNode oddFiltersJsonArray = getJson(oddFiltersUrl);
        if (oddFiltersJsonArray == null) {
            throw new IOException("No odd filters json got from " + oddFiltersUrl);
        }
        Iterator<JsonNode> oddFilters = oddFiltersJsonArray.elements();
        Map<Integer, String> oddNamesMap = new HashMap<>();
        while (oddFilters.hasNext()) {
            JsonNode oddFilter = oddFilters.next();
            int filterId = oddFilter.get("filter_id").asInt();
            String name = oddFilter.get("translation").asText();
            oddNamesMap.put(filterId, name);
        }
        return oddNamesMap;
    }

}
