package com.aerus.cli;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import com.aerus.buffer.BufferPoolManager;
import com.aerus.buffer.LruReplacer;
import com.aerus.engine.ExecutionEngine;
import com.aerus.engine.scheduling.BankersAlgorithm;
import com.aerus.engine.scheduling.SchedulingPolicy;
import com.aerus.engine.scheduling.Task;
import com.aerus.engine.scheduling.TaskScheduler;
import com.aerus.index.BTree;
import com.aerus.storage.FileDiskManager;
import com.aerus.storage.Page;


public class AerusDashboard extends JFrame {

    private final JTextArea logArea;
    private final JTextField queryField;
    private final GraphPanel cacheGraph;
    private final GraphPanel taskGraph;

    // Components
    private FileDiskManager diskManager;
    private BufferPoolManager bufferPool;
    private BTree bTree;
    private ExecutionEngine executionEngine;
    private TaskScheduler scheduler;
    private BankersAlgorithm bankersAlgorithm;

    private Long currentTxnId = null;
    private final AtomicInteger taskCounter = new AtomicInteger(0);

    public AerusDashboard() {
        super("AERUS Database Engine Dashboard");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(950, 680);
        setLayout(new BorderLayout(5, 5));

        // Initialize Engine Components
        initEngine();

        // Center Panel: Log area + Query input
        JPanel centerPanel = new JPanel(new BorderLayout(5, 5));
        centerPanel.setBorder(BorderFactory.createTitledBorder("Live Execution Log & Console"));

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setBackground(new Color(20, 24, 30));
        logArea.setForeground(new Color(130, 240, 150));
        logArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        JScrollPane scrollPane = new JScrollPane(logArea);
        centerPanel.add(scrollPane, BorderLayout.CENTER);

        // Query input panel at the bottom of center
        JPanel queryPanel = new JPanel(new BorderLayout(5, 5));
        JLabel lblPrompt = new JLabel(" SQL / Query > ");
        lblPrompt.setFont(new Font("Consolas", Font.BOLD, 13));
        queryField = new JTextField();
        queryField.setFont(new Font("Consolas", Font.PLAIN, 13));
        JButton btnRunQuery = new JButton("Execute");

        queryField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    runManualQuery();
                }
            }
        });
        btnRunQuery.addActionListener(e -> runManualQuery());

        queryPanel.add(lblPrompt, BorderLayout.WEST);
        queryPanel.add(queryField, BorderLayout.CENTER);
        queryPanel.add(btnRunQuery, BorderLayout.EAST);
        centerPanel.add(queryPanel, BorderLayout.SOUTH);

        // North Controls Panel: quick triggers for OS concepts
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        JButton btnSubmitTask = new JButton("Simulate CPU Task");
        JButton btnFetchPage = new JButton("Random Page Access");
        JButton btnBankers = new JButton("Banker's Resource Req");
        JButton btnHelp = new JButton("Query Help");

        btnSubmitTask.addActionListener(e -> triggerTask());
        btnFetchPage.addActionListener(e -> triggerFetchPage());
        btnBankers.addActionListener(e -> triggerBankers());
        btnHelp.addActionListener(e -> logQueryHelp());

        controls.add(btnSubmitTask);
        controls.add(btnFetchPage);
        controls.add(btnBankers);
        controls.add(btnHelp);

        // East Panel: Visual Graphs
        JPanel eastPanel = new JPanel(new GridLayout(2, 1, 10, 10));
        eastPanel.setPreferredSize(new Dimension(340, 0));
        eastPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        cacheGraph = new GraphPanel("Critical Cache Hit Rate (%)", new Color(0, 200, 255));
        taskGraph = new GraphPanel("CPU Scheduling Latency (ms)", new Color(255, 100, 150));

        eastPanel.add(cacheGraph);
        eastPanel.add(taskGraph);

        add(controls, BorderLayout.NORTH);
        add(centerPanel, BorderLayout.CENTER);
        add(eastPanel, BorderLayout.EAST);

        log("=================================================================");
        log(" AERUS Database Engine Initialized");
        log(" - Storage: 4KB Pages via FileDiskManager");
        log(" - Buffer Pool: LRU Replacer with Hit/Miss Tracking");
        log(" - Index: Disk-backed B+Tree");
        log(" - Engine: Transactions (BEGIN, COMMIT, ROLLBACK) & CRUD");
        log(" - OS Layer: CPU TaskScheduler & Banker's Resource Allocation");
        log(" Type queries below (e.g. INSERT 10 500 or GET 10) and press Enter.");
        log("=================================================================");
    }

    private void initEngine() {
        try {
            Path dbPath = Paths.get("aerus_gui.db");
            diskManager = new FileDiskManager(dbPath);
            LruReplacer replacer = new LruReplacer(10);
            bufferPool = new BufferPoolManager(10, diskManager, replacer);
            int rootId = diskManager.getRootPageId();
            bTree = new BTree(bufferPool, rootId);
            if (rootId == com.aerus.common.Constants.INVALID_PAGE_ID) {
                bTree.createIfEmpty();
                diskManager.setRootPageId(bTree.getRootPageId());
            }
            executionEngine = new ExecutionEngine(bufferPool, bTree);

            scheduler = new TaskScheduler(SchedulingPolicy.FCFS);
            scheduler.start();

            int[] available = {10, 10, 10};
            int[][] max = {{7, 5, 3}, {3, 2, 2}};
            int[][] alloc = {{0, 1, 0}, {2, 0, 0}};
            bankersAlgorithm = new BankersAlgorithm(available, max, alloc);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void runManualQuery() {
        String q = queryField.getText().trim();
        if (q.isEmpty()) return;
        queryField.setText("");
        log("> " + q);

        try {
            String res = executionEngine.executeQuery(q, currentTxnId);
            if (res.startsWith("TXN_STARTED")) {
                currentTxnId = Long.parseLong(res.split(" ")[1]);
                log("Transaction [" + currentTxnId + "] ACTIVE.");
            } else if (res.contains("committed") || res.contains("aborted")) {
                currentTxnId = null;
            }
            log(res);

            // Update hit rate on cache
            updateCacheGraph();
        } catch (Exception ex) {
            log("EXECUTION ERROR: " + ex.getMessage());
        }
    }

    private void logQueryHelp() {
        log("--- Query Commands ---");
        log("  INSERT <key> <val>           e.g. INSERT 101 5000");
        log("  GET <key>                    e.g. GET 101");
        log("  DELETE <key>                 e.g. DELETE 101");
        log("  SCAN <start> <end>           e.g. SCAN 1 200");
        log("  BEGIN / START                Start transaction");
        log("  COMMIT                       Commit changes");
        log("  ROLLBACK / ABORT             Undo transaction changes");
        log("----------------------");
    }

    private void updateCacheGraph() {
        int hits = bufferPool.getBufferHits();
        int misses = bufferPool.getBufferMisses();
        int total = hits + misses;
        if (total > 0) {
            int rate = (int) (((double) hits / total) * 100);
            cacheGraph.addData(rate);
        }
    }

    private void triggerTask() {
        int id = taskCounter.incrementAndGet();
        int cost = (int) (Math.random() * 50) + 10;

        Task task = new Task(id, "Job-" + id, cost, 1, () -> {
            try {
                Thread.sleep(cost);
                SwingUtilities.invokeLater(() -> {
                    log("[CPU] Completed Task Job-" + id + " (Duration: " + cost + "ms)");
                    taskGraph.addData(cost);
                });
            } catch (InterruptedException ignored) {}
        });

        scheduler.submit(task);
        log("[CPU] Dispatched Job-" + id + " (Cost: " + cost + "ms) to Scheduler.");
    }

    private void triggerFetchPage() {
        try {
            int pageId = (int) (Math.random() * 8) + 1;
            while (diskManager.getPageCount() <= pageId) {
                diskManager.allocatePage();
            }
            Page p = bufferPool.fetchPage(pageId);
            if (p != null) {
                bufferPool.unpinPage(pageId, false);
            }
            int hits = bufferPool.getBufferHits();
            int misses = bufferPool.getBufferMisses();
            log("[Cache] Fetched Page " + pageId + " -> Hits: " + hits + ", Misses: " + misses);
            updateCacheGraph();
        } catch (IOException ex) {
            log("Fetch error: " + ex.getMessage());
        }
    }

    private void triggerBankers() {
        int pId = (int) (Math.random() * 2);
        int[] req = {(int) (Math.random() * 3), (int) (Math.random() * 2), (int) (Math.random() * 2)};
        try {
            boolean granted = bankersAlgorithm.requestResources(pId, req);
            log("[Banker] Process P" + pId + " requested " + java.util.Arrays.toString(req) + " -> "
                    + (granted ? "GRANTED (System Safe)" : "DENIED (Potential Deadlock Avoided)"));
        } catch (IllegalArgumentException e) {
            log("[Banker] Request denied (exceeded claim).");
        }
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            AerusDashboard dashboard = new AerusDashboard();
            dashboard.setLocationRelativeTo(null);
            dashboard.setVisible(true);
        });
    }
}
