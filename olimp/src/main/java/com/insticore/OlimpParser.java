package com.insticore;

import org.asynchttpclient.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class OlimpParser {
    private final Logger logger = LoggerFactory.getLogger(OlimpParser.class);
    private AsyncHttpClient asyncHttpClient;
    private ExecutorService executorService;


    private Lock sportLock = new ReentrantLock();
    private Lock tournamentLock = new ReentrantLock();
    private Lock eventLock = new ReentrantLock();

    public OlimpParser() {
        asyncHttpClient = Dsl.asyncHttpClient(Dsl.config().setUserAgent("Mozilla/5.0").setRequestTimeout(100000));
        executorService = Executors.newCachedThreadPool();
    }

    public void parseToConsole() {
        loadSports();
    }

    private void loadSports() {
        //Use predefined sports urls as they are preselected and stable
        //Map them to their titles
        Map<String, String> preparedSportsMap = new HashMap<>();
        preparedSportsMap.put("https://504f0c.olimp0bae.top/betting/soccer", "Футбол");
        preparedSportsMap.put("https://504f0c.olimp0bae.top/betting/tennis", "Тенис");
        preparedSportsMap.put("https://504f0c.olimp0bae.top/betting/basketball", "Баскетбол");
        preparedSportsMap.put("https://504f0c.olimp0bae.top/betting/hockey", "Хоккей");
        preparedSportsMap.put("https://504f0c.olimp0bae.top/betting/volleyball", "Воллейбол");
        preparedSportsMap.put("https://504f0c.olimp0bae.top/betting/rugby-league", "Рэгби-Лига");
        preparedSportsMap.put("https://504f0c.olimp0bae.top/betting/rugby-union", "Рэгби-Союз");

        //How many sports will be loaded as async tasks
        CountDownLatch countDownLatch = new CountDownLatch(preparedSportsMap.size());

       for (HashMap.Entry<String, String> sportEntry : preparedSportsMap.entrySet()) {
            String sportUrl = sportEntry.getKey();
            String sportName = sportEntry.getValue();
            loadSportAsync(sportUrl, sportName, countDownLatch);
        }

        try {
            //Wait until all async tasks are completed
            countDownLatch.await();
            asyncHttpClient.close();
        } catch (Exception e) {
            logger.error("Parsing sports finished with exception " + e.getMessage(), e);
        }
    }

    /**
     *  Loads tournaments for the given sport async
     * @param url
     * @param name
     * @param countDownLatch
     */
    private void loadSportAsync(String url, String name, CountDownLatch countDownLatch) {
        Request request = asyncHttpClient.prepareGet(url).build();
        ListenableFuture<Response> listenableFuture = asyncHttpClient
                .executeRequest(request);
        listenableFuture.addListener(() -> {
            try {
                Response response = listenableFuture.get();
                //Make sure sport printing is an atomic operation
                sportLock.lock();
                System.out.println(name); // Print sport
                loadTournaments(response);
                sportLock.unlock();

            } catch (Exception e) {
                logger.error("Failed to load sport: " + url, e);
            }  finally {
                //The task is completed
                countDownLatch.countDown();
            }
        }, executorService);
    }

    /**
     *  Parses the page with tournaments
     * @param response
     */
    private void loadTournaments(Response response) {
        Document document = Jsoup.parse(response.getResponseBody());
        if (response.getStatusCode() != 200) {
            return;
        }
        Elements tournaments = document.select("table.live_main_table a[data-s]");

        //How many tournaments will be loaded as async tasks
        CountDownLatch countDownLatch = new CountDownLatch(tournaments.size());

        for (org.jsoup.nodes.Element tournament : tournaments) {
            String href = tournament.attr("href");
            String name = cleanName(tournament.text(), "(");

            //Skip "Итоги"-like tournaments as they are not relevant to odds
            if (name.contains("Итоги")) {
                countDownLatch.countDown();
                continue;
            }
            String tournamentUrl = "https://504f0c.olimp0bae.top/betting/" + href;
            loadTournamentAsync(tournamentUrl, name, countDownLatch);
        }
        try {
            //Wait until all async loading tasks are completed
            countDownLatch.await();
        } catch (InterruptedException e) {
            logger.error("Loading tournaments interrupted", e);
        }
    }

    /**
     * Loads all events for the given tournament async
     * @param url
     * @param name
     * @param countDownLatch
     */
    private void loadTournamentAsync(String url, String name, CountDownLatch countDownLatch) {
        Request request = asyncHttpClient.prepareGet(url).build();
        ListenableFuture<Response> listenableFuture = asyncHttpClient
                .executeRequest(request);
        listenableFuture.addListener(() -> {
            try {
                Response response = listenableFuture.get();
                //Make sure tournament printing is atomic
                tournamentLock.lock();
                System.out.println(name); // Print tournament
                loadEvents(response);
                tournamentLock.unlock();
            } catch (Exception e) {
                logger.error("Failed to load tournament: " + url, e);
            } finally {
                //The task is completed
                countDownLatch.countDown();
            }

        }, executorService);
    }

    /**
     * Loads all events for the tournament in the response
     * @param response
     */
    private void loadEvents(Response response) {
        Document document = Jsoup.parse(response.getResponseBody());
        Element table = document.select("table.koeftable2").first();
        if (table == null) {
            return;
        }
        Elements events = table.select("tbody > tr.hi");

        //How many events will be loaded as async tasks
        CountDownLatch countDownLatch = new CountDownLatch(events.size());

        for (org.jsoup.nodes.Element event : events) {
            //Here is the event link
            Element gameNameLine = event.select("div.gameNameLine > font > b > span > a").first();
            String href = gameNameLine.attr("href");
            String eventUrl = "https://504f0c.olimp0bae.top" + href;
            loadEventAsync(eventUrl, countDownLatch);
        }
        try {
            //Wait until all events are loaded async
            countDownLatch.await();
        } catch (InterruptedException e) {
            logger.error("Loading events interrupted", e);
        }
    }

    /**
     * Loads odds for the give event
     * @param url
     * @param countDownLatch
     */
    private void loadEventAsync(String url, CountDownLatch countDownLatch) {
        Request request = asyncHttpClient.prepareGet(url).build();
        ListenableFuture<Response> listenableFuture = asyncHttpClient
                .executeRequest(request);
        listenableFuture.addListener(() -> {
            try {
                Response response = listenableFuture.get();
                //Make sure event printing is atomic
                eventLock.lock();
                loadEvent(response);
                eventLock.unlock();
            } catch (Exception e) {
                logger.error("Failed to load tournament: " + url, e);
            } finally {
                //The task is completed
                countDownLatch.countDown();
            }

        }, executorService);
    }

    /**
     * Read all odds for the event in response
     * @param response
     */
    private void loadEvent(Response response) {
        Document eventPage = Jsoup.parse(response.getResponseBody());
        Element table = eventPage.select("table.koeftable2").first();
        if (table == null) {
            return;
        }
        Element element = table.select("tbody > tr.hi").first();
        //Here is the event date ant title
        String date = element.select("td").first().text();
        Element gameNameLine = element.select("div.gameNameLine > font > b > span").first();
        String name = gameNameLine.text();

        Element odds = eventPage.select("div.tab > div[data-match-id-show]").first();
        if (odds == null || odds.children().size() == 0) {
            //No odds in the event, nothing to do here
            return;
        }
        String id =  odds.attr("data-match-id-show");

        System.out.println(String.join(", ", name, date, id));  // Print event

        Elements markets = odds.children();

        for (Element market : markets) {
            if ("b".equals(market.tagName())) {
                //Here is the market title
                String marketName = cleanName(market.text(), ":");
                System.out.println(marketName); // Print market
            } else if ("nobr".equals(market.tagName())) {
                //Here are the odds of the market
                //There are different variants where odds elements are located
                //1. Title and value under the nobr tag itself
                List<Node> nodes = market.childNodes();
                String oddName = "";
                Element googleStatIssue = market.select("span.googleStatIssue").first();
                if (googleStatIssue != null) {
                    Element googleStatIssueName = googleStatIssue.select("span.googleStatIssueName").first();
                    List<Node> googleStatIssueNameNodes = googleStatIssueName.childNodes();
                    if (googleStatIssueNameNodes.size() > 1) {
                        //2.  Title and value in the googleStatIssueName
                        nodes = googleStatIssueNameNodes;
                    } else if (googleStatIssueNameNodes.size() == 1 && googleStatIssueNameNodes.get(0) instanceof TextNode) {
                        //3. Title in googleStatIssueName and value in googleStatIssue
                        oddName = cleanName(googleStatIssueName.text(), " - ");
                        Element span = googleStatIssue.select("span[data-id]").first();
                        String oddId = span.attr("data-id");
                        String oddValue = span.select("b.value_js > span#googleStatKef").text();
                        //Print odd
                        System.out.println(String.join(", ", "\t\t" + oddName, oddValue, oddId));
                        continue;
                    }
                }
                //If title and value are under the same element
                for (Node node : nodes) {
                    if (node instanceof TextNode) {
                        oddName = cleanName(((TextNode) node).text(), " - ");
                    } else if (node instanceof Element) {
                        Element span = (Element) node;
                        String oddId = span.attr("data-id");
                        String oddValue = span.select("b.value_js > span#googleStatKef").text();
                        //Print odd
                        System.out.println(String.join(", ", "\t\t" + oddName, oddValue, oddId));
                    }
                }
            }
        }


    }

    /**
     * Removes the given title from the name and trim it
     * Generalized method for names cleaning
     * @param name
     * @param tail
     * @return
     */
    private String cleanName(String name, String tail){
        if (name == null || name.isEmpty()) {
            return "";
        }
        String cleanName = name;
        if (cleanName.contains(tail)) {
            cleanName = cleanName.substring(0, cleanName.indexOf(tail));
        }
        return cleanName.trim();
    }
}
