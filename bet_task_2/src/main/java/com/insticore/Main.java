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
import org.w3c.dom.Text;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {

    private static AsyncHttpClient asyncHttpClient;
    private static ExecutorService tournamentsExecutorService = Executors.newCachedThreadPool();
    private static ExecutorService eventsExecutorService = Executors.newCachedThreadPool();
    private static Logger logger = LoggerFactory.getLogger(Main.class);

    static {
        asyncHttpClient = Dsl.asyncHttpClient(Dsl.config().setUserAgent("Mozilla/5.0").setRequestTimeout(100000));

    }

    private static int threadsCount = 0;

    public static void main(String[] args) {
        String[] sportUrls = {
                "https://504f0c.olimp0bae.top/betting/soccer"/*,
                "https://504f0c.olimp0bae.top/betting/tennis",
                "https://504f0c.olimp0bae.top/betting/basketball",
                "https://504f0c.olimp0bae.top/betting/hockey",
                "https://504f0c.olimp0bae.top/betting/volleyball",
                "https://504f0c.olimp0bae.top/betting/rugby-league",
                "https://504f0c.olimp0bae.top/betting/rugby-union"*/
        };
        List<String> sportUrlsList = Arrays.asList(sportUrls);
        CountDownLatch countDownLatch = new CountDownLatch(sportUrlsList.size());

        for (String sportUrl : sportUrlsList) {
            loadSportAsync(sportUrl, "soccer",  countDownLatch);
        }

        try {
            countDownLatch.await();
            asyncHttpClient.close();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
/*        try {
            executorService.awaitTermination(30, TimeUnit.MINUTES);
            asyncHttpClient.close();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }*/

    }

    private static void loadSportAsync(String url, String name, CountDownLatch countDownLatch) {
        Request request = asyncHttpClient.prepareGet(url).build();
        ListenableFuture<Response> listenableFuture = asyncHttpClient
                .executeRequest(request);
        listenableFuture.addListener(() -> {
            try {
                Response response = listenableFuture.get();
                System.out.println("Sport: " + name);
                loadTournaments(response);
            } catch (Exception e) {
                logger.error("Failed to load sport: " + url);
            }  finally {
                countDownLatch.countDown();
            }
        }, tournamentsExecutorService);

    }

    private static void loadTournaments(Response response) {
        Document document = Jsoup.parse(response.getResponseBody());
        Elements tournaments = document.select("tr.bg > td > a");
        CountDownLatch countDownLatch = new CountDownLatch(tournaments.size());
        for (org.jsoup.nodes.Element tournament : tournaments) {
            String href = tournament.attr("href");
            String name = tournament.text();
            name = name.substring(0, name.indexOf("("));
            if (name.contains("Итоги")) {
                countDownLatch.countDown();
                continue;
            }
            String tournamentUrl = "https://504f0c.olimp0bae.top/betting/" + href;
            loadTournamentAsync(tournamentUrl, name, countDownLatch);
        }

        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static void loadTournamentAsync(String url, String name, CountDownLatch countDownLatch) {
        Request request = asyncHttpClient.prepareGet(url).build();
        ListenableFuture<Response> listenableFuture = asyncHttpClient
                .executeRequest(request);
        listenableFuture.addListener(() -> {
            try {
                Response response = listenableFuture.get();
                System.out.println(name);
                loadEvents(response);
            } catch (Exception e) {
                logger.error("Failed to load tournament: " + url, e);
            } finally {
                countDownLatch.countDown();
            }

        }, tournamentsExecutorService);

/*         try {
            eventsExecutorService.awaitTermination(15, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }*/
    }

    private static void loadEvents(Response response) {
        Document document = Jsoup.parse(response.getResponseBody());
        Element table = document.select("table.koeftable2").first();
        Elements events = table.select("tbody > tr.hi");


        String date = table.select("tbody > tr.gameDateRow").first().text().trim();
        for (org.jsoup.nodes.Element event : events) {
            Element gameNameLine = event.select("div.gameNameLine > font > b > span > a").first();
            String href = gameNameLine.attr("href");
            String name = gameNameLine.text();
            String id = href.substring(href.indexOf("mid=") + 4);
            String time = event.getElementsByTag("td").first().ownText();
            System.out.println(String.join(", ", name, date + " " + time, id));
            try {
                String eventUrl = "https://504f0c.olimp0bae.top" + href;
                Document eventPage = Jsoup.connect(eventUrl).get();
                Element odds = eventPage.select("div[data-match-id-show=" + id + "]").first();
                if (odds.childNodeSize() <= 0) {
                    continue;
                }
                Elements markets = odds.children();

                for (Element market : markets) {
                    if ("b".equals(market.tagName())) {
                        System.out.println(market.text());
                    } else if ("nobr".equals(market.tagName())) {

                            String oddName = market.select("span.googleStatIssue > span.googleStatIssueName").text();
                            if (oddName == null || oddName.isEmpty()) {
                                List<Node> nodes = market.childNodes();
                                for (Node node : nodes) {
                                    if (node instanceof TextNode) {
                                        oddName = ((TextNode) node).text();
                                    } else if (node instanceof Element) {
                                        Element span = (Element) node;
                                        String oddId = span.attr("data-id");
                                        String oddValue = span.select("b.value_js > span#googleStatKef").text();
                                        System.out.println(String.join(", ", "\t\t" + oddName, oddValue, oddId));
                                    }
                                }
                            } else {
                                String oddId = market.getElementsByAttribute("data-id").first().attr("data-id");
                                String oddValue = market.select("span > b.value_js > span#googleStatKef").text();
                                System.out.println(String.join(", ", "\t\t" + oddName, oddValue, oddId));
                            }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

        }

    }
}
