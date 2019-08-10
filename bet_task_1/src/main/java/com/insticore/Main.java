package com.insticore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.asynchttpclient.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class Main {
    private static AsyncHttpClient asyncHttpClient;

    private static Map<Integer, String> sportsMap = new HashMap<>();
    private static ExecutorService executorService = Executors.newCachedThreadPool();

    static {
        asyncHttpClient = Dsl.asyncHttpClient(Dsl.config().setUserAgent("Mozilla/5.0"));
    }

    public static void main(String[] args) throws IOException, TimeoutException {
        JsonNode prematchMenu = getJson("https://nodejs08.tglab.io/cache/20/en/en/Europe%2FPrague/prematch-menu.json");
        Iterator<JsonNode> sports = prematchMenu.get("data").get("sports").elements();

        while (sports.hasNext()) {
            JsonNode sport = sports.next();
            sportsMap.put(sport.get("id").asInt(), sport.get("title").asText());

        }
        final Integer[] DESIRED_SPORTS = {1, 2, 3, 4, 6, 12};
        List<Integer> desiredSportsList = Arrays.asList(DESIRED_SPORTS);
        Iterator<JsonNode> sportsMenu = prematchMenu.get("sports_menu").elements();

        while (sportsMenu.hasNext()) {
            JsonNode sportsMenuItem = sportsMenu.next();
            Integer sportId = sportsMenuItem.get("id").asInt();
            if (desiredSportsList.contains(sportId)) {
                String sportName = sportsMap.get(sportId);
                System.out.println(sportName);
                Iterator<JsonNode> countries = sportsMenuItem.get("countries").elements();
                while (countries.hasNext()) {
                    JsonNode country = countries.next();
                    Iterator<JsonNode> tournaments = country.get("tournaments").elements();
                    while (tournaments.hasNext()) {
                        JsonNode tournament = tournaments.next();
                        String tournamentId = tournament.asText();

                        loadTournamentAsync(tournamentId);
                    }
                }
            }

        }
        try {
            executorService.awaitTermination(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }

    private static JsonNode getJson(String url) {
        BoundRequestBuilder getRequest = asyncHttpClient.prepareGet(url);
        Future<Response> responseFuture = getRequest.execute();
        try {
            Response response = responseFuture.get();
            String json = response.getResponseBody();
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.readTree(json);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static void loadTournamentAsync(String tournamentId) {
        String url = String.format("https://nodejs08.tglab.io/cache/20/en/en/%s/prematch-by-tournaments.json", tournamentId);

        Request request = asyncHttpClient.prepareGet(url).build();

        ListenableFuture<Response> listenableFuture = asyncHttpClient
                .executeRequest(request);
        listenableFuture.addListener(() -> {
            try {
                Response response = listenableFuture.get();
                loadTournament(response);
            } catch (Exception e) {
                e.printStackTrace();
            }

        }, executorService);
    }

    private static void loadTournament(Response response) throws IOException {
        String tournamentsJson = response.getResponseBody();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode tournamentInfo = objectMapper.readTree(tournamentsJson);
        JsonNode eventsNode = tournamentInfo.get("events");
        Iterator<JsonNode> events = eventsNode.elements();

        String tournamentName = tournamentInfo.get("events").get(0).get("tournament_name").get("en").asText();
        if (tournamentName.matches(".*Winner.*")) {
            return;
        }
        System.out.println(tournamentName);
        while (events.hasNext()) {
            JsonNode event = events.next();
            String eventId = event.get("id").asText();
            String eventInfoUrl = String.format("https://nodejs08.tglab.io/cache/20/en/en/%s/single-pre-event.json", eventId);
            JsonNode eventInfo = getJson(eventInfoUrl);
            JsonNode info = eventInfo.get("info");
            String startDate = info.get("date_start").asText();
            Iterator<JsonNode> teams = info.get("teams").elements();
            String eventName = teams.next().asText();
            while (teams.hasNext()) {
                JsonNode team = teams.next();
                String nextTeamName = team.textValue();
                if (nextTeamName != null && !nextTeamName.isEmpty()) {
                    eventName += " vs. " + nextTeamName;
                }
            }
            System.out.println(String.join(", ", eventName, startDate, eventId));

            Iterator<JsonNode> odds = eventInfo.get("odds").elements();

            String sportId = info.get("sport_id").asText();


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

            String oddFiltersUrl = String.format("https://nodejs.tglab.io/cache/%s/0/en/20/odd-filters.json", sportId);
            JsonNode oddFiltersJsonArray = getJson(oddFiltersUrl);
            Iterator<JsonNode> oddFilters = oddFiltersJsonArray.elements();
            Map<Integer, String> oddNamesMap = new HashMap<>();
            while (oddFilters.hasNext()) {
                JsonNode oddFilter = oddFilters.next();
                int filterId = oddFilter.get("filter_id").asInt();
                String name = oddFilter.get("translation").asText();
                oddNamesMap.put(filterId, name);
            }
            for (HashMap.Entry<Integer, List<Odd>> marketEntry : oddsMap.entrySet()) {
                int filterId = marketEntry.getKey();
                String marketName = oddNamesMap.get(filterId);
                System.out.println(marketName);
                for (Odd odd : marketEntry.getValue()) {
                    System.out.println(String.format("\t\t%s, %.2f, %d", odd.getName(), odd.getValue(), odd.getId()));
                }
            }
        }
    }
}
