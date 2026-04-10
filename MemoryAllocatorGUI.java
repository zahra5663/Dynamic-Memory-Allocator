import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import javax.swing.plaf.basic.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class MemoryAllocatorGUI extends JFrame {

    // ── Colour palette ────────────────────────────────────────
    static final Color BG_DARK      = new Color(15,  17,  26);
    static final Color BG_PANEL     = new Color(22,  27,  42);
    static final Color BG_CARD      = new Color(30,  36,  54);
    static final Color BG_INPUT     = new Color(38,  44,  62);
    static final Color ACCENT_BLUE  = new Color(64, 156, 255);
    static final Color ACCENT_TEAL  = new Color(32, 210, 180);
    static final Color ACCENT_PURPLE= new Color(160, 100, 255);
    static final Color ACCENT_ORANGE= new Color(255, 160,  50);
    static final Color FREE_COLOR   = new Color(32,  190, 120);
    static final Color ALLOC_COLOR  = new Color(255,  90,  90);
    static final Color TEXT_PRIMARY = new Color(220, 230, 255);
    static final Color TEXT_DIM     = new Color(130, 145, 180);
    static final Color BORDER_COLOR = new Color(55,  65,  95);

    static final Color[] PROCESS_COLORS = {
        new Color(64,156,255), new Color(255,90,90),  new Color(32,210,180),
        new Color(255,160,50), new Color(160,100,255),new Color(255,200,50),
        new Color(100,220,100),new Color(255,120,180)
    };

    // ── Data model ────────────────────────────────────────────
    static class Block {
        int id, startAddr, size, processID;
        boolean isFree;
        Block(int id, int start, int size) {
            this.id=id; this.startAddr=start; this.size=size;
            this.isFree=true; this.processID=-1;
        }
        Block copy() {
            Block b=new Block(id,startAddr,size);
            b.isFree=isFree; b.processID=processID; return b;
        }
    }

    // ── Engine ────────────────────────────────────────────────
    static class MemoryEngine {
        List<Block> blocks;
        int nextFitPtr = 0;
        StringBuilder log = new StringBuilder();

        MemoryEngine(List<Block> initial) {
            blocks = new ArrayList<>();
            for (Block b : initial) blocks.add(b.copy());
        }

        void recompute() {
            int addr=0;
            for (int i=0;i<blocks.size();i++) {
                blocks.get(i).id=i;
                blocks.get(i).startAddr=addr;
                addr+=blocks.get(i).size;
            }
        }

        void splitIfNeeded(int idx, int reqSize) {
            int left = blocks.get(idx).size - reqSize;
            if (left > 20) {
                Block nb = new Block(-1, 0, left);
                blocks.get(idx).size = reqSize;
                blocks.add(idx+1, nb);
                recompute();
                log.append("  ✂ SPLIT: "+reqSize+" KB allocated, "+left+" KB returned to pool.\n");
            }
        }

        void coalesce() {
            boolean merged=true;
            while (merged) {
                merged=false;
                for (int i=0;i<blocks.size()-1;i++) {
                    if (blocks.get(i).isFree && blocks.get(i+1).isFree) {
                        int combined=blocks.get(i).size+blocks.get(i+1).size;
                        log.append("  🔗 MERGE: Block "+blocks.get(i).id
                            +" ("+blocks.get(i).size+" KB) + Block "+blocks.get(i+1).id
                            +" ("+blocks.get(i+1).size+" KB) → "+combined+" KB\n");
                        blocks.get(i).size=combined;
                        blocks.remove(i+1);
                        recompute();
                        merged=true; break;
                    }
                }
            }
        }

        boolean allocate(int size, int pid, String algo) {
            log.append("\n▶ ["+algo+"] Allocate "+size+" KB → P"+pid+"\n");
            int idx=-1;
            switch (algo) {
                case "First-Fit": idx=firstFit(size); break;
                case "Next-Fit":  idx=nextFit(size);  break;
                case "Best-Fit":  idx=bestFit(size);  break;
                case "Worst-Fit": idx=worstFit(size); break;
            }
            if (idx==-1) {
                log.append("  ✗ FAILED — no block large enough for "+size+" KB\n");
                return false;
            }
            splitIfNeeded(idx, size);
            blocks.get(idx).isFree=false;
            blocks.get(idx).processID=pid;
            log.append("  ✓ SUCCESS — P"+pid+" allocated "+size+" KB at address "+blocks.get(idx).startAddr+"\n");
            return true;
        }

        void deallocate(int pid, String algo) {
            log.append("\n◀ ["+algo+"] Deallocate P"+pid+"\n");
            boolean found=false;
            for (Block b : blocks) {
                if (!b.isFree && b.processID==pid) {
                    b.isFree=true; b.processID=-1; found=true;
                    log.append("  ✓ Block "+b.id+" ("+b.size+" KB) freed.\n");
                }
            }
            if (!found) { log.append("  ✗ P"+pid+" not found.\n"); return; }
            coalesce();
        }

        int firstFit(int size) {
            for (int i=0;i<blocks.size();i++)
                if (blocks.get(i).isFree && blocks.get(i).size>=size) return i;
            return -1;
        }

        int nextFit(int size) {
            int n=blocks.size();
            for (int i=0;i<n;i++) {
                int idx=(nextFitPtr+i)%n;
                if (blocks.get(idx).isFree && blocks.get(idx).size>=size) {
                    nextFitPtr=(idx+1)%n; return idx;
                }
            }
            return -1;
        }

        int bestFit(int size) {
            int best=-1, bestSz=Integer.MAX_VALUE;
            for (int i=0;i<blocks.size();i++)
                if (blocks.get(i).isFree && blocks.get(i).size>=size && blocks.get(i).size<bestSz) {
                    bestSz=blocks.get(i).size; best=i;
                }
            return best;
        }

        int worstFit(int size) {
            int worst=-1, worstSz=-1;
            for (int i=0;i<blocks.size();i++)
                if (blocks.get(i).isFree && blocks.get(i).size>=size && blocks.get(i).size>worstSz) {
                    worstSz=blocks.get(i).size; worst=i;
                }
            return worst;
        }

        int totalFree()    { return blocks.stream().filter(b->b.isFree).mapToInt(b->b.size).sum(); }
        int freeBlocks()   { return (int)blocks.stream().filter(b->b.isFree).count(); }
        int largestFree()  { return blocks.stream().filter(b->b.isFree).mapToInt(b->b.size).max().orElse(0); }
        boolean extFrag()  { return freeBlocks()>1; }
    }

    // ── GUI fields ────────────────────────────────────────────
    Map<String, MemoryEngine> engines = new LinkedHashMap<>();
    List<Block> initialBlocks;
    String[] algos = {"First-Fit","Next-Fit","Best-Fit","Worst-Fit"};
    Color[] algoColors = {ACCENT_BLUE, ACCENT_TEAL, ACCENT_ORANGE, ACCENT_PURPLE};

    JTabbedPane algoTabs;
    Map<String, MemoryVisualPanel>  visualPanels = new LinkedHashMap<>();
    Map<String, JTable>             blockTables  = new LinkedHashMap<>();
    Map<String, DefaultTableModel>  tableModels  = new LinkedHashMap<>();
    Map<String, JLabel[]>           fragLabels   = new LinkedHashMap<>();
    JTextArea logArea;
    JSpinner sizeSpinner, pidSpinner;
    JComboBox<String> algoCombo, opCombo;
    JLabel statusLabel;
    JPanel statsPanel;
    Map<String, JLabel> statValues = new HashMap<>();

    // ── Entry point ───────────────────────────────────────────
    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception e){}
        SwingUtilities.invokeLater(() -> new MemoryAllocatorGUI().setVisible(true));
    }

    MemoryAllocatorGUI() {
        setTitle("Dynamic Memory Allocation Simulator  —  CS 330");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1280, 860);
        setMinimumSize(new Dimension(1100, 700));
        setLocationRelativeTo(null);

        buildInitialBlocks();
        resetEngines();

        getContentPane().setBackground(BG_DARK);
        setLayout(new BorderLayout(0,0));

        add(buildTopBar(),   BorderLayout.NORTH);
        add(buildCenter(),   BorderLayout.CENTER);
        add(buildBottom(),   BorderLayout.SOUTH);

        refreshAll();
        animateTitleBar();
    }

    void buildInitialBlocks() {
        int[][] data = {{80},{120},{40},{200},{30},{100},{60},{150},{90},{154}};
        initialBlocks = new ArrayList<>();
        int addr=0;
        for (int i=0;i<data.length;i++) {
            initialBlocks.add(new Block(i, addr, data[i][0]));
            addr+=data[i][0];
        }
    }

    void resetEngines() {
        engines.clear();
        for (String a : algos) engines.put(a, new MemoryEngine(initialBlocks));
    }

    // ── TOP BAR ───────────────────────────────────────────────
    JPanel buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(BG_PANEL);
        bar.setBorder(new MatteBorder(0,0,1,0, BORDER_COLOR));
        bar.setPreferredSize(new Dimension(0,64));

        // Left — title
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT,18,0));
        left.setOpaque(false);
        JLabel dot = new JLabel("●");
        dot.setFont(new Font("Arial",Font.PLAIN,28));
        dot.setForeground(ACCENT_BLUE);
        JLabel title = new JLabel("Memory Allocation Simulator");
        title.setFont(new Font("Segoe UI",Font.BOLD,20));
        title.setForeground(TEXT_PRIMARY);
        JLabel sub = new JLabel("CS 330 · OS Lab · BESE-30");
        sub.setFont(new Font("Segoe UI",Font.PLAIN,13));
        sub.setForeground(TEXT_DIM);
        left.add(dot); left.add(title); left.add(sub);
        bar.add(left, BorderLayout.WEST);

        // Right — reset + run demo
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT,12,0));
        right.setOpaque(false);
        JButton demo  = makeBtn("▶ Run Demo",  ACCENT_TEAL);
        JButton reset = makeBtn("↺ Reset",     ACCENT_BLUE);
        JButton stress= makeBtn("⚡ Stress Test", ACCENT_ORANGE);
        demo.addActionListener(e -> runDemo());
        reset.addActionListener(e -> { resetEngines(); refreshAll(); appendLog("=== RESET — all engines reloaded ===\n"); });
        stress.addActionListener(e -> runStressTest());
        right.add(stress); right.add(demo); right.add(reset);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    // ── CENTER ────────────────────────────────────────────────
    JSplitPane buildCenter() {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
            buildLeftPanel(), buildRightPanel());
        split.setDividerLocation(370);
        split.setDividerSize(4);
        split.setBorder(null);
        split.setBackground(BG_DARK);
        return split;
    }

    // ── LEFT PANEL — controls ─────────────────────────────────
    JPanel buildLeftPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(BG_PANEL);
        p.setBorder(new EmptyBorder(14,14,14,14));

        p.add(sectionLabel("OPERATION"));
        p.add(Box.createVerticalStrut(8));

        // Operation type
        opCombo = styledCombo(new String[]{"Allocate","Deallocate"});
        p.add(labeledRow("Operation:", opCombo));
        p.add(Box.createVerticalStrut(8));

        // Algorithm
        algoCombo = styledCombo(algos);
        p.add(labeledRow("Algorithm:", algoCombo));
        p.add(Box.createVerticalStrut(8));

        // Size
        sizeSpinner = new JSpinner(new SpinnerNumberModel(75,1,512,5));
        styleSpinner(sizeSpinner);
        p.add(labeledRow("Size (KB):", sizeSpinner));
        p.add(Box.createVerticalStrut(8));

        // PID
        pidSpinner = new JSpinner(new SpinnerNumberModel(1,1,99,1));
        styleSpinner(pidSpinner);
        p.add(labeledRow("Process ID:", pidSpinner));
        p.add(Box.createVerticalStrut(14));

        // Execute button
        JButton exec = makeBtn("  ▶  EXECUTE", ACCENT_BLUE);
        exec.setFont(new Font("Segoe UI",Font.BOLD,15));
        exec.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        exec.setAlignmentX(Component.CENTER_ALIGNMENT);
        exec.addActionListener(e -> executeOperation());
        p.add(exec);

        p.add(Box.createVerticalStrut(20));
        p.add(sectionLabel("STATISTICS"));
        p.add(Box.createVerticalStrut(8));
        p.add(buildStatsPanel());

        p.add(Box.createVerticalStrut(20));
        p.add(sectionLabel("LEGEND"));
        p.add(Box.createVerticalStrut(8));
        p.add(buildLegend());

        p.add(Box.createVerticalGlue());
        return p;
    }

    JPanel buildStatsPanel() {
        JPanel p = new JPanel(new GridLayout(0,1,4,4));
        p.setOpaque(false);
        String[] keys = {"Total Memory","Total Free","Allocated","Free Blocks","Largest Free"};
        String[] vals = {"1024 KB","1024 KB","0 KB","10","200 KB"};
        for (int i=0;i<keys.length;i++) {
            JPanel row = new JPanel(new BorderLayout());
            row.setBackground(BG_CARD);
            row.setBorder(new CompoundBorder(
                new LineBorder(BORDER_COLOR,1,true),
                new EmptyBorder(6,10,6,10)));
            JLabel k = new JLabel(keys[i]);
            k.setFont(new Font("Segoe UI",Font.PLAIN,12));
            k.setForeground(TEXT_DIM);
            JLabel v = new JLabel(vals[i]);
            v.setFont(new Font("Segoe UI",Font.BOLD,13));
            v.setForeground(ACCENT_TEAL);
            statValues.put(keys[i], v);
            row.add(k, BorderLayout.WEST);
            row.add(v, BorderLayout.EAST);
            p.add(row);
        }
        statsPanel = p;
        return p;
    }

    JPanel buildLegend() {
        JPanel p = new JPanel(new GridLayout(2,2,6,6));
        p.setOpaque(false);
        p.add(legendItem(FREE_COLOR,  "FREE Block"));
        p.add(legendItem(ALLOC_COLOR, "ALLOCATED"));
        p.add(legendItem(ACCENT_TEAL, "Split event"));
        p.add(legendItem(ACCENT_ORANGE,"Merge event"));
        return p;
    }

    JPanel legendItem(Color c, String txt) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT,6,2));
        p.setOpaque(false);
        JLabel dot = new JLabel("■");
        dot.setFont(new Font("Arial",Font.PLAIN,16));
        dot.setForeground(c);
        JLabel lbl = new JLabel(txt);
        lbl.setFont(new Font("Segoe UI",Font.PLAIN,12));
        lbl.setForeground(TEXT_DIM);
        p.add(dot); p.add(lbl);
        return p;
    }

    // ── RIGHT PANEL — tabs + log ──────────────────────────────
    JPanel buildRightPanel() {
        JPanel p = new JPanel(new BorderLayout(0,6));
        p.setBackground(BG_DARK);
        p.setBorder(new EmptyBorder(10,6,6,10));

        algoTabs = new JTabbedPane();
        algoTabs.setBackground(BG_PANEL);
        algoTabs.setForeground(TEXT_PRIMARY);
        algoTabs.setFont(new Font("Segoe UI",Font.BOLD,13));
        styleTabPane(algoTabs);

        for (int i=0;i<algos.length;i++) {
            algoTabs.addTab(algos[i], buildAlgoTab(algos[i], algoColors[i]));
        }
        p.add(algoTabs, BorderLayout.CENTER);

        // Log area
        JPanel logCard = new JPanel(new BorderLayout());
        logCard.setBackground(BG_CARD);
        logCard.setBorder(new CompoundBorder(
            new LineBorder(BORDER_COLOR,1,true),
            new EmptyBorder(0,0,0,0)));
        logCard.setPreferredSize(new Dimension(0,160));

        JLabel logTitle = new JLabel("  📋  Operation Log");
        logTitle.setFont(new Font("Segoe UI",Font.BOLD,13));
        logTitle.setForeground(TEXT_PRIMARY);
        logTitle.setBackground(BG_PANEL);
        logTitle.setOpaque(true);
        logTitle.setBorder(new EmptyBorder(6,8,6,8));
        logCard.add(logTitle, BorderLayout.NORTH);

        logArea = new JTextArea();
        logArea.setFont(new Font("Consolas",Font.PLAIN,12));
        logArea.setBackground(new Color(12,14,22));
        logArea.setForeground(new Color(180,220,180));
        logArea.setEditable(false);
        logArea.setMargin(new Insets(8,10,8,10));
        JScrollPane ls = new JScrollPane(logArea);
        ls.setBorder(null);
        ls.getVerticalScrollBar().setUI(new BasicScrollBarUI(){
            protected void configureScrollBarColors(){ thumbColor=BORDER_COLOR; }
        });
        logCard.add(ls, BorderLayout.CENTER);

        JButton clearBtn = new JButton("Clear");
        clearBtn.setFont(new Font("Segoe UI",Font.PLAIN,11));
        clearBtn.setForeground(TEXT_DIM);
        clearBtn.setBackground(BG_PANEL);
        clearBtn.setBorder(new EmptyBorder(4,10,4,10));
        clearBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        clearBtn.addActionListener(e->{ logArea.setText(""); });
        JPanel logBot = new JPanel(new FlowLayout(FlowLayout.RIGHT,6,2));
        logBot.setBackground(BG_PANEL);
        logBot.add(clearBtn);
        logCard.add(logBot, BorderLayout.SOUTH);

        p.add(logCard, BorderLayout.SOUTH);
        return p;
    }

    JPanel buildAlgoTab(String algo, Color accent) {
        JPanel tab = new JPanel(new BorderLayout(0,8));
        tab.setBackground(BG_DARK);
        tab.setBorder(new EmptyBorder(10,8,8,8));

        // Visual memory bar
        MemoryVisualPanel vis = new MemoryVisualPanel(algo, accent);
        visualPanels.put(algo, vis);
        vis.setPreferredSize(new Dimension(0, 80));
        tab.add(vis, BorderLayout.NORTH);

        // Table
        String[] cols = {"BLK","Start","Size(KB)","Status","Process"};
        DefaultTableModel model = new DefaultTableModel(cols, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };
        tableModels.put(algo, model);
        JTable table = new JTable(model);
        table.setFont(new Font("Consolas",Font.PLAIN,13));
        table.setForeground(TEXT_PRIMARY);
        table.setBackground(BG_CARD);
        table.setGridColor(BORDER_COLOR);
        table.setRowHeight(28);
        table.setSelectionBackground(new Color(64,156,255,60));
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(false);
        JTableHeader hdr = table.getTableHeader();
        hdr.setFont(new Font("Segoe UI",Font.BOLD,13));
        hdr.setBackground(BG_PANEL);
        hdr.setForeground(accent);
        hdr.setBorder(new MatteBorder(0,0,1,0,BORDER_COLOR));
        table.setDefaultRenderer(Object.class, new BlockTableRenderer());
        blockTables.put(algo, table);
        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(new LineBorder(BORDER_COLOR,1));
        sp.getViewport().setBackground(BG_CARD);
        sp.getVerticalScrollBar().setUI(new BasicScrollBarUI(){
            protected void configureScrollBarColors(){ thumbColor=BORDER_COLOR; }
        });
        tab.add(sp, BorderLayout.CENTER);

        // Fragmentation info row
        JPanel fragRow = new JPanel(new GridLayout(1,4,6,0));
        fragRow.setOpaque(false);
        String[] fk = {"Total Free","Free Blocks","Largest Free","Ext. Frag"};
        JLabel[] fl = new JLabel[4];
        for (int i=0;i<4;i++) {
            JPanel card = new JPanel(new BorderLayout());
            card.setBackground(BG_CARD);
            card.setBorder(new CompoundBorder(
                new LineBorder(BORDER_COLOR,1,true),
                new EmptyBorder(6,10,6,10)));
            JLabel k = new JLabel(fk[i], SwingConstants.CENTER);
            k.setFont(new Font("Segoe UI",Font.PLAIN,11));
            k.setForeground(TEXT_DIM);
            fl[i] = new JLabel("—", SwingConstants.CENTER);
            fl[i].setFont(new Font("Segoe UI",Font.BOLD,14));
            fl[i].setForeground(accent);
            card.add(k, BorderLayout.NORTH);
            card.add(fl[i], BorderLayout.CENTER);
            fragRow.add(card);
        }
        fragLabels.put(algo, fl);
        tab.add(fragRow, BorderLayout.SOUTH);
        return tab;
    }

    // ── BOTTOM STATUS BAR ─────────────────────────────────────
    JPanel buildBottom() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(BG_PANEL);
        bar.setBorder(new MatteBorder(1,0,0,0,BORDER_COLOR));
        bar.setPreferredSize(new Dimension(0,30));
        statusLabel = new JLabel("  ✓  Ready — select algorithm and operation, then click Execute");
        statusLabel.setFont(new Font("Segoe UI",Font.PLAIN,12));
        statusLabel.setForeground(ACCENT_TEAL);
        JLabel ver = new JLabel("CS330 · BESE-30 · Spring 2026  ");
        ver.setFont(new Font("Segoe UI",Font.PLAIN,12));
        ver.setForeground(TEXT_DIM);
        bar.add(statusLabel, BorderLayout.WEST);
        bar.add(ver, BorderLayout.EAST);
        return bar;
    }

    // ── OPERATIONS ────────────────────────────────────────────
    void executeOperation() {
        String algo = (String)algoCombo.getSelectedItem();
        String op   = (String)opCombo.getSelectedItem();
        int size = (int)sizeSpinner.getValue();
        int pid  = (int)pidSpinner.getValue();
        MemoryEngine eng = engines.get(algo);

        if ("Allocate".equals(op)) {
            boolean ok = eng.allocate(size, pid, algo);
            setStatus(ok ? "✓ Allocated "+size+" KB to P"+pid+" ["+algo+"]"
                        : "✗ Allocation FAILED — "+size+" KB not available ["+algo+"]", ok);
        } else {
            eng.deallocate(pid, algo);
            setStatus("◀ Deallocated P"+pid+" ["+algo+"]", true);
        }
        appendLog(eng.log.toString());
        eng.log.setLength(0);
        refreshAlgo(algo);
        updateStats();
    }

    void runDemo() {
        appendLog("\n══════════ DEMO SEQUENCE ══════════\n");
        int[][] ops = {{75,1},{35,2},{190,3},{55,4},{100,5}};
        Timer t = new Timer();
        int delay=0;
        for (String algo : algos) {
            final String a = algo;
            // allocations
            for (int[] op : ops) {
                final int sz=op[0], pid=op[1];
                t.schedule(new TimerTask(){ public void run(){
                    SwingUtilities.invokeLater(()->{
                        MemoryEngine e=engines.get(a);
                        e.allocate(sz,pid,a);
                        appendLog(e.log.toString()); e.log.setLength(0);
                        refreshAlgo(a); updateStats();
                    });
                }}, delay+=300);
            }
            // deallocations
            for (int pid : new int[]{2,4}) {
                final int p=pid;
                t.schedule(new TimerTask(){ public void run(){
                    SwingUtilities.invokeLater(()->{
                        MemoryEngine e=engines.get(a);
                        e.deallocate(p,a);
                        appendLog(e.log.toString()); e.log.setLength(0);
                        refreshAlgo(a); updateStats();
                    });
                }}, delay+=300);
            }
            // P6 60KB
            t.schedule(new TimerTask(){ public void run(){
                SwingUtilities.invokeLater(()->{
                    MemoryEngine e=engines.get(a);
                    e.allocate(60,6,a);
                    appendLog(e.log.toString()); e.log.setLength(0);
                    refreshAlgo(a); updateStats();
                });
            }}, delay+=300);
            // P7 500KB (expected fail for most)
            t.schedule(new TimerTask(){ public void run(){
                SwingUtilities.invokeLater(()->{
                    MemoryEngine e=engines.get(a);
                    e.allocate(500,7,a);
                    appendLog(e.log.toString()); e.log.setLength(0);
                    refreshAlgo(a); updateStats();
                });
            }}, delay+=300);
        }
        setStatus("▶ Demo running — watch all 4 algorithms...", true);
    }

    void runStressTest() {
        appendLog("\n══════════ STRESS TEST (10 random requests each) ══════════\n");
        Random rnd = new Random();
        Timer t = new Timer();
        int delay=0;
        for (String algo : algos) {
            final String a=algo;
            for (int i=0;i<10;i++) {
                final int sz = rnd.nextInt(141)+10;
                final int pid= 100+i;
                t.schedule(new TimerTask(){ public void run(){
                    SwingUtilities.invokeLater(()->{
                        MemoryEngine e=engines.get(a);
                        e.allocate(sz,pid,a);
                        appendLog(e.log.toString()); e.log.setLength(0);
                        refreshAlgo(a); updateStats();
                    });
                }}, delay);
                delay+=200;
            }
        }
        setStatus("⚡ Stress test running...", true);
    }

    // ── REFRESH ───────────────────────────────────────────────
    void refreshAll() {
        for (String a : algos) refreshAlgo(a);
        updateStats();
    }

    void refreshAlgo(String algo) {
        MemoryEngine eng = engines.get(algo);
        DefaultTableModel model = tableModels.get(algo);
        model.setRowCount(0);
        for (Block b : eng.blocks) {
            model.addRow(new Object[]{
                "BLK "+b.id,
                b.startAddr,
                b.size,
                b.isFree ? "FREE" : "ALLOCATED",
                b.isFree ? "---" : "P"+b.processID
            });
        }
        visualPanels.get(algo).setBlocks(eng.blocks);
        visualPanels.get(algo).repaint();
        JLabel[] fl = fragLabels.get(algo);
        fl[0].setText(eng.totalFree()+" KB");
        fl[1].setText(String.valueOf(eng.freeBlocks()));
        fl[2].setText(eng.largestFree()+" KB");
        fl[3].setText(eng.extFrag() ? "YES" : "NO");
        fl[3].setForeground(eng.extFrag() ? ALLOC_COLOR : FREE_COLOR);
    }

    void updateStats() {
        // Use first-fit engine as representative for global stats
        MemoryEngine e = engines.get("First-Fit");
        int total=0; for (Block b:initialBlocks) total+=b.size;
        int free=e.totalFree(), alloc=total-free;
        statValues.get("Total Memory").setText(total+" KB");
        statValues.get("Total Free").setText(free+" KB");
        statValues.get("Allocated").setText(alloc+" KB");
        statValues.get("Free Blocks").setText(String.valueOf(e.freeBlocks()));
        statValues.get("Largest Free").setText(e.largestFree()+" KB");
    }

    void appendLog(String txt) {
        SwingUtilities.invokeLater(()->{
            logArea.append(txt);
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    void setStatus(String msg, boolean ok) {
        statusLabel.setText("  "+(ok?"✓":"✗")+"  "+msg);
        statusLabel.setForeground(ok ? ACCENT_TEAL : ALLOC_COLOR);
    }

    // ── VISUAL MEMORY BAR ─────────────────────────────────────
    static class MemoryVisualPanel extends JPanel {
        List<Block> blocks = new ArrayList<>();
        Color accent;
        String algo;
        MemoryVisualPanel(String algo, Color accent) {
            this.algo=algo; this.accent=accent;
            setBackground(BG_CARD);
            setBorder(new CompoundBorder(
                new LineBorder(BORDER_COLOR,1,true),
                new EmptyBorder(8,8,8,8)));
        }
        void setBlocks(List<Block> b) {
            blocks = new ArrayList<>(b);
        }
        int totalSize() { return blocks.stream().mapToInt(b->b.size).sum(); }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2=(Graphics2D)g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (blocks.isEmpty()) return;

            int pw=getWidth()-16, ph=getHeight()-24, px=8, py=8;
            int total=totalSize();

            // Label row
            g2.setFont(new Font("Segoe UI",Font.BOLD,11));
            g2.setColor(accent);
            g2.drawString(algo+" Memory Map", px, py+10);
            py+=16;
            ph-=16;

            int barH=ph-14;
            float x=px;
            for (Block b : blocks) {
                float w = (float)b.size/total*pw;
                Color base = b.isFree ? FREE_COLOR : PROCESS_COLORS[Math.abs(b.processID)%PROCESS_COLORS.length];
                if (b.isFree) base=FREE_COLOR;

                // Fill
                GradientPaint gp = new GradientPaint(x,py, base.brighter(), x,py+barH, base.darker());
                g2.setPaint(gp);
                g2.fillRoundRect((int)x, py, Math.max((int)w-1,1), barH, 4,4);

                // Border
                g2.setColor(BG_DARK);
                g2.drawRoundRect((int)x, py, Math.max((int)w-1,1), barH, 4,4);

                // Label inside block if wide enough
                if (w>32) {
                    g2.setColor(Color.WHITE);
                    g2.setFont(new Font("Segoe UI",Font.BOLD,9));
                    String lbl = b.isFree ? b.size+"KB" : "P"+b.processID;
                    FontMetrics fm=g2.getFontMetrics();
                    int lx=(int)(x+(w-fm.stringWidth(lbl))/2);
                    g2.drawString(lbl, lx, py+barH/2+4);
                }
                x+=w;
            }

            // Address labels
            g2.setFont(new Font("Consolas",Font.PLAIN,9));
            g2.setColor(TEXT_DIM);
            x=px;
            for (Block b : blocks) {
                float w=(float)b.size/total*pw;
                g2.drawString(String.valueOf(b.startAddr),(int)x, py+barH+12);
                x+=w;
            }
            // last address
            g2.drawString(String.valueOf(total),(int)(px+pw)-18, py+barH+12);
        }
    }

    // ── TABLE RENDERER ────────────────────────────────────────
    static class BlockTableRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable t, Object val, boolean sel, boolean foc, int row, int col) {
            super.getTableCellRendererComponent(t,val,sel,foc,row,col);
            setHorizontalAlignment(col==0||col==3||col==4 ? CENTER : RIGHT);
            setBackground(BG_CARD);
            setForeground(TEXT_PRIMARY);
            setBorder(new EmptyBorder(0,8,0,8));
            String status = (String)t.getValueAt(row,3);
            if ("FREE".equals(status)) {
                if (col==3) setForeground(FREE_COLOR);
            } else {
                if (col==3) setForeground(ALLOC_COLOR);
                if (col==4) setForeground(ACCENT_BLUE);
            }
            if (row%2==1) setBackground(new Color(35,42,62));
            return this;
        }
    }

    // ── UI HELPERS ────────────────────────────────────────────
    JButton makeBtn(String text, Color color) {
        JButton b = new JButton(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2=(Graphics2D)g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                if (getModel().isPressed())
                    g2.setColor(color.darker());
                else if (getModel().isRollover())
                    g2.setColor(color.brighter());
                else
                    g2.setColor(new Color(color.getRed(),color.getGreen(),color.getBlue(),40));
                g2.fillRoundRect(0,0,getWidth(),getHeight(),8,8);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawRoundRect(0,0,getWidth()-1,getHeight()-1,8,8);
                super.paintComponent(g);
            }
        };
        b.setFont(new Font("Segoe UI",Font.BOLD,13));
        b.setForeground(color);
        b.setFocusPainted(false);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setPreferredSize(new Dimension(140,36));
        return b;
    }

    JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Segoe UI",Font.BOLD,11));
        l.setForeground(TEXT_DIM);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    JPanel labeledRow(String label, JComponent comp) {
        JPanel row = new JPanel(new BorderLayout(8,0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE,38));
        JLabel lbl = new JLabel(label);
        lbl.setFont(new Font("Segoe UI",Font.PLAIN,13));
        lbl.setForeground(TEXT_DIM);
        lbl.setPreferredSize(new Dimension(90,0));
        row.add(lbl, BorderLayout.WEST);
        row.add(comp, BorderLayout.CENTER);
        return row;
    }

    JComboBox<String> styledCombo(String[] items) {
        JComboBox<String> c = new JComboBox<>(items);
        c.setFont(new Font("Segoe UI",Font.PLAIN,13));
        c.setBackground(BG_INPUT);
        c.setForeground(TEXT_PRIMARY);
        c.setBorder(new LineBorder(BORDER_COLOR,1,true));
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE,34));
        return c;
    }

    void styleSpinner(JSpinner s) {
        s.setFont(new Font("Segoe UI",Font.PLAIN,13));
        s.setBackground(BG_INPUT);
        s.setForeground(TEXT_PRIMARY);
        s.setBorder(new LineBorder(BORDER_COLOR,1,true));
        s.setMaximumSize(new Dimension(Integer.MAX_VALUE,34));
        ((JSpinner.DefaultEditor)s.getEditor()).getTextField().setBackground(BG_INPUT);
        ((JSpinner.DefaultEditor)s.getEditor()).getTextField().setForeground(TEXT_PRIMARY);
        ((JSpinner.DefaultEditor)s.getEditor()).getTextField().setCaretColor(TEXT_PRIMARY);
    }

    void styleTabPane(JTabbedPane tp) {
        tp.setUI(new BasicTabbedPaneUI(){
            @Override protected void installDefaults() {
                super.installDefaults();
                highlight = BG_PANEL;
                lightHighlight = BG_PANEL;
                shadow = BG_PANEL;
                darkShadow = BG_PANEL;
                focus = BG_PANEL;
            }
        });
    }

    void animateTitleBar() {
        // subtle pulse on the dot every 2s
        Timer t = new Timer();
        t.scheduleAtFixedRate(new TimerTask(){
            int tick=0;
            public void run(){
                tick++;
                // nothing visual needed; keep timer for potential future use
            }
        },0,2000);
    }
}