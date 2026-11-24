package vn.trainingstats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

/**
 * M7.4: nhập điểm đồng thời từ nhiều giám khảo (luôn chạy) và kiểm thử tải trang chủ với 2.000 người, 50 người cùng lúc
 * qua HTTP thật (chỉ chạy khi có {@code -Dload=true}, mất vài phút):
 * {@code ./mvnw test -Dtest=LoadTest -Dload=true}.
 */
class LoadTest extends M3TestBase {

    // ------------------------------------------------------------------ nhập điểm đồng thời

    @Test
    void chamDiemDongThoiNhieuGiamKhao() throws Exception {
        MockHttpSession sa = login(SA_USERNAME, SA_PASSWORD);
        int examiners = 6;
        int learners = 60;
        long classId = newClass(sa);
        List<MockHttpSession> sessions = new ArrayList<>();
        Long[] teacherIds = new Long[examiners];
        for (int t = 0; t < examiners; t++) {
            teacherIds[t] = ensureUser("m7-gk-" + t, "USER", "0004", PW, "ACTIVE");
        }
        setTeachers(sa, classId, teacherIds);
        for (int t = 0; t < examiners; t++) {
            sessions.add(login("m7-gk-" + t, PW));
        }
        List<Long> pids = new ArrayList<>();
        for (int i = 0; i < learners; i++) {
            pids.add(addParticipant(sa, classId, person(sa, "0003", "Tải " + i)));
        }

        // Mỗi giám khảo chấm cả 60 người theo thứ tự xáo trộn khác nhau, cùng lúc: mỗi người bị 6 bên tranh nhau chấm.
        ExecutorService pool = Executors.newFixedThreadPool(examiners);
        CountDownLatch start = new CountDownLatch(1);
        Map<Long, List<Integer>> statuses = new ConcurrentHashMap<>();
        List<Future<?>> jobs = new ArrayList<>();
        try {
            for (int t = 0; t < examiners; t++) {
                MockHttpSession s = sessions.get(t);
                List<Long> order = new ArrayList<>(pids);
                Collections.shuffle(order, new Random(t));
                String score = String.valueOf(4 + t);
                jobs.add(pool.submit(() -> {
                    start.await();
                    for (long pid : order) {
                        Map<String, Object> body = new HashMap<>();
                        body.put("score", new BigDecimal(score));
                        int st = call(post("/api/v1/classes/" + classId + "/participants/" + pid + "/result"), s, body).status();
                        statuses.computeIfAbsent(pid, k -> Collections.synchronizedList(new ArrayList<>())).add(st);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : jobs) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        for (long pid : pids) {
            assertThat(statuses.get(pid)).as("người học %d", pid).hasSize(examiners)
                    .containsOnlyOnce(201).allMatch(st -> st == 201 || st == 409);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM result r JOIN class_participant p ON p.id = r.participant_id "
                + "WHERE p.class_id = ?", Integer.class, classId)).isEqualTo(learners);
    }

    // ------------------------------------------------------------------ kiểm thử tải trang chủ

    /** Một người dùng ảo: phiên riêng (cookie tự giữ như trình duyệt), gọi API như khi mở trang chủ. */
    private final class VirtualUser {
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        private final Map<String, String> cookies = new ConcurrentHashMap<>();
        private final String base = "http://localhost:" + port;
        private final boolean manager;

        VirtualUser(boolean manager) {
            this.manager = manager;
        }

        private HttpRequest.Builder request(String path) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Accept", "application/json");
            if (!cookies.isEmpty()) {
                b.header("Cookie", String.join("; ", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()));
            }
            return b;
        }

        private <T> HttpResponse<T> keep(HttpResponse<T> r) {
            for (String c : r.headers().allValues("set-cookie")) {
                String pair = c.split(";", 2)[0];
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
            return r;
        }

        int get(String path) throws Exception {
            return keep(http.send(request(path).GET().build(), HttpResponse.BodyHandlers.discarding())).statusCode();
        }

        void login(String username) throws Exception {
            // Như core/api.js: lấy token CSRF từ /auth/csrf (tên header và giá trị máy chủ trả về) rồi gửi kèm khi ghi.
            var csrf = json.readTree(keep(http.send(request("/api/v1/auth/csrf").GET().build(),
                    HttpResponse.BodyHandlers.ofString())).body());
            int st = keep(http.send(request("/api/v1/auth/login")
                    .header("Content-Type", "application/json").header(csrf.get("headerName").asText(), csrf.get("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username", username, "password", PW))))
                    .build(), HttpResponse.BodyHandlers.discarding())).statusCode();
            assertThat(st).as("đăng nhập %s", username).isEqualTo(200);
            assertThat(get("/api/v1/me")).as("phiên của %s", username).isEqualTo(200);
        }

        /** Mở trang chủ: trang, /me, rồi các API của trang chủ gọi song song như home.js. Trả số lỗi (khác 200). */
        int openHome() throws Exception {
            int errors = 0;
            for (String p : List.of("/index.html", "/js/app.js", "/css/components.css", "/api/v1/me")) {
                errors += get(p) == 200 ? 0 : 1;
            }
            List<String> calls = new ArrayList<>(List.of("/api/v1/classes?size=5", "/api/v1/notifications/unread-count",
                    "/api/v1/notifications?size=5"));
            if (manager) {
                calls.addAll(List.of("/api/v1/users?size=1", "/api/v1/requests?status=PENDING&size=1",
                        "/api/v1/stats/overview?group=unit", "/api/v1/stats/overview?kind=UNIT", "/api/v1/classes?ungraded=true&size=1"));
            } else {
                calls.add("/api/v1/me/classes");
            }
            List<CompletableFuture<HttpResponse<Void>>> fs = calls.stream().map(p -> http.sendAsync(
                    request(p).GET().build(), HttpResponse.BodyHandlers.discarding())).toList();
            for (CompletableFuture<HttpResponse<Void>> f : fs) {
                errors += f.get().statusCode() == 200 ? 0 : 1;
            }
            return errors;
        }
    }

    @Test
    void taiHaiNghinNguoiNamMuoiNguoiCungLuc() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("load"), "chạy với -Dload=true");
        String tag = uniq("tai");
        List<Long> unitIds = jdbc.queryForList("SELECT id FROM unit WHERE level = 2 ORDER BY id", Long.class);
        long saId = userIdOf(SA_USERNAME);
        MockHttpSession sa = login(SA_USERNAME, SA_PASSWORD);
        long period = period(sa);
        // 2.000 người, 40 nội dung kiểm tra, 10.000 lượt tham gia, 80% đã có điểm.
        tx().executeWithoutResult(s -> {
            List<Object[]> users = new ArrayList<>();
            for (int i = 0; i < 2000; i++) {
                users.add(new Object[] {"Người " + i, "nguoi " + i, unitIds.get(i % unitIds.size()), tag + "-" + i});
            }
            jdbc.batchUpdate("""
                    INSERT INTO app_user (kind, last_name, first_name, search_text, unit_id, role, status, personal_code, category)
                    VALUES ('PERSON', 'Tải', ?, ?, ?, 'USER', 'ACTIVE', ?, 'SQ')
                    """, users);
            List<Long[]> people = jdbc.query("SELECT id, unit_id FROM app_user WHERE personal_code LIKE ?",
                    (rs, i) -> new Long[] {rs.getLong(1), rs.getLong(2)}, tag + "-%");
            for (int c = 0; c < 40; c++) {
                jdbc.update("INSERT INTO exam_class (period_id, name, exam_date) VALUES (?, ?, ?)",
                        period, tag + " nội dung " + c, "2026-11-" + (10 + c % 18));
                long classId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
                List<Object[]> parts = new ArrayList<>();
                for (int k = 0; k < 250; k++) {
                    Long[] p = people.get((c * 250 + k) % people.size());
                    parts.add(new Object[] {classId, p[0], p[1]});
                }
                jdbc.batchUpdate("INSERT INTO class_participant (class_id, user_id, unit_id_snapshot) VALUES (?, ?, ?)", parts);
                jdbc.update("""
                        INSERT INTO result (participant_id, score_x10, graded_by)
                        SELECT id, (id * 7) % 101, ? FROM class_participant WHERE class_id = ? AND id % 5 <> 0
                        """, saId, classId);
            }
        });

        // 50 người dùng ảo: 10 Admin (trang chủ có thống kê), 40 người dùng thường.
        int n = 50;
        List<VirtualUser> vus = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            boolean manager = i < 10;
            String username = "tai-" + i;
            ensureUser(username, manager ? "ADMIN" : "USER", manager ? "0002" : "0002.0001", PW, "ACTIVE");
            VirtualUser vu = new VirtualUser(manager);
            vu.login(username);
            vus.add(vu);
        }
        vus.get(0).openHome(); // làm nóng

        int rounds = 3;
        List<Long> times = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errors = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> jobs = new ArrayList<>();
        try {
            for (VirtualUser vu : vus) {
                jobs.add(pool.submit(() -> {
                    start.await();
                    for (int r = 0; r < rounds; r++) {
                        long t0 = System.nanoTime();
                        errors.addAndGet(vu.openHome());
                        times.add((System.nanoTime() - t0) / 1_000_000);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : jobs) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        List<Long> sorted = new ArrayList<>(times);
        Collections.sort(sorted);
        long p50 = sorted.get(sorted.size() / 2);
        long p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
        long max = sorted.get(sorted.size() - 1);
        System.out.printf("KIEM THU TAI: %d lan mo trang chu, p50=%d ms, p95=%d ms, max=%d ms, loi=%d%n",
                sorted.size(), p50, p95, max, errors.get());
        assertThat(errors.get()).isZero();
        assertThat(p95).as("95%% lần mở trang chủ dưới 2 giây (ms)").isLessThan(2000);
    }
}
