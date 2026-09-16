import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/** Source-faithful Swing reconstruction of the ImageJ GenericDialog for documentation. */
public final class GuiScreenshotter {
    private static int row;

    public static void main(String[] args) throws Exception {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        File out = new File(args.length == 0 ? "GUI_Screenshots" : args[0]);
        out.mkdirs();
        render(buildMain(), new File(out, "01_Log_Ratio_Registration.png"));
        render(buildAdvanced(), new File(out, "02_Advanced_Parameters.png"));
        render(buildBatch(), new File(out, "03_Log_Ratio_Registration_Batch.png"));
        render(buildBatchProgress(), new File(out, "04_Batch_Progress.png"));
        render(buildSweepSetup(), new File(out, "05_Parameter_Sweep_Setup.png"));
        render(buildSweepResults(), new File(out, "06_Parameter_Sweep_Results.png"));
    }

    private static void render(JDialog dialog, File target) throws Exception {
        dialog.pack();
        Container content = dialog.getContentPane();
        Dimension preferred = content.getPreferredSize();
        content.setSize(preferred);
        layout(content);
        BufferedImage image = new BufferedImage(preferred.width, preferred.height,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        content.printAll(graphics);
        graphics.dispose();
        dialog.dispose();
        ImageIO.write(image, "png", target);
        System.out.println(target.getAbsolutePath());
    }

    private static JDialog buildMain() {
        JDialog dialog = new JDialog((Frame) null, "Relative-Intensity Pattern Registration", false);
        JPanel body = new JPanel(new GridBagLayout());
        body.setBorder(new EmptyBorder(12, 14, 8, 14));
        row = 0;
        message(body, "Choose a recipe, the channel used to estimate movement, and whether to");
        message(body, "use the whole recording. Fitting, rotation, and output controls are under");
        message(body, "Advanced settings.");
        choice(body, "Recipe", "Landmarks (phase contrast / brightfield)",
                "Bright/dim references (fluorescence / bioluminescence)",
                "Moving cells (biological foreground)");
        choice(body, "Channel used to estimate movement", "Channel 1", "Channel 2", "Channel 3");
        check(body, "Use longitudinal mode (whole recording)", true);
        check(body, "Show advanced settings before running", false);
        message(body, "Longitudinal mode uses the fixed whole-recording route. Moving cells uses");
        message(body, "a benchmark-backed frame-to-frame recipe and therefore keeps longitudinal mode off.");
        JPanel sweep = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        sweep.add(new JButton("Advanced parameter sweep..."));
        add(body, sweep, 0, 2);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(new JButton("Cancel"));
        JButton register = new JButton("Register");
        register.setFont(register.getFont().deriveFont(Font.BOLD));
        buttons.add(register);
        JPanel root = new JPanel(new BorderLayout());
        root.add(new JScrollPane(body, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.setPreferredSize(new Dimension(610, 500));
        return dialog;
    }

    private static JDialog buildAdvanced() {
        JDialog dialog = new JDialog((Frame) null, "Relative-Intensity Pattern Registration - Advanced Parameters", false);
        JPanel body = new JPanel(new GridBagLayout());
        body.setBorder(new EmptyBorder(12, 14, 8, 14));
        row = 0;
        message(body, "Loaded recommendation: sparse low-light steady drift");
        message(body, "Automatic choice: remove the least spatially informative 25% of pixels");
        message(body, "using the unfiltered image only to choose the mask.");
        message(body, "All values below are editable.");
        section(body, "Input preparation");
        choice(body, "Preprocessing for provisional movement estimate", "Median denoising (3 by 3)",
                "None", "Gaussian smoothing (0.7 pixel)", "Median denoising (3 by 3)",
                "Photon-noise stabilisation", "Mild sharpening");
        section(body, "Pixel removal (optional second pass)");
        message(body, "A scoring copy chooses the mask; original unfiltered pixels drive the final fit.");
        choice(body, "Pixel removal strategy", "Remove least spatially informative pixels",
                "None", "Remove most frame-to-frame unstable pixels", "Remove lowest combined anchor trust");
        choice(body, "Filter used only to choose the mask", "None", "Gaussian smoothing (0.7 pixel)",
                "Median denoising (3 by 3)", "Photon-noise stabilisation", "Mild sharpening");
        field(body, "Eligible pixels removed (%)", "25.0");
        section(body, "Estimation and reference");
        choice(body, "Reference strategy", "MULTILAG", "CONSECUTIVE", "FIXED", "ROLLING");
        field(body, "Reference frame (1-based)", "1");
        field(body, "Lags", "1,2,4,8,16");
        field(body, "Rolling template window", "5");
        section(body, "Log-ratio fit");
        choice(body, "Robust weighting", "HUBER", "TUKEY", "LEAST_SQUARES");
        choice(body, "Pixel support", "ALL", "GRADIENT", "MUTUAL_NOISE_GRADIENT");
        field(body, "Gradient multiplier", "0.5");
        field(body, "Log epsilon", "1.0");
        field(body, "Exclude below percentile", "off");
        field(body, "Exclude above percentile", "off");
        check(body, "Remove additive background", false);
        field(body, "Background percentile", "1.0");
        section(body, "Search and performance");
        field(body, "Estimation scale (0-1; output stays full size)", "1.0");
        check(body, "Estimate maximum shift automatically", true);
        field(body, "Maximum shift when manual (pixels)", "30.0");
        field(body, "Step outlier repair (median deviations; 0 = off)", "6.0");
        field(body, "Maximum iterations per level", "25");
        field(body, "Maximum sampled pixels", "200000");
        field(body, "Minimum usable pixel fraction", "0.10");
        field(body, "Worker threads (0 = automatic)", "0");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(new JButton("Cancel"));
        JButton use = new JButton("Use these parameters");
        use.setFont(use.getFont().deriveFont(Font.BOLD));
        buttons.add(use);
        JPanel root = new JPanel(new BorderLayout());
        root.add(new JScrollPane(body, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        root.setPreferredSize(new Dimension(720, 1220));
        dialog.setContentPane(root);
        dialog.setPreferredSize(new Dimension(720, 1220));
        return dialog;
    }

    private static JDialog buildBatch() {
        JDialog dialog = new JDialog((Frame) null, "Relative-Intensity Pattern Registration Batch", false);
        JPanel body = new JPanel(new GridBagLayout());
        body.setBorder(new EmptyBorder(12, 14, 8, 14));
        row = 0;
        message(body, "Apply one registration setup to every TIFF stack in a folder.");
        message(body, "Stacks are processed one at a time and keep their subfolder layout.");
        field(body, "Input folder", "D:/recordings/day 1");
        field(body, "Output folder", "D:/recordings/day 1 corrected");
        check(body, "Include subfolders", true);
        check(body, "Overwrite existing corrected stacks", false);
        section(body, "Shared registration setup");
        choice(body, "Recipe", "Landmarks (phase contrast / brightfield)",
                "Bright/dim references (fluorescence / bioluminescence)",
                "Moving cells (biological foreground)");
        field(body, "Channel used to estimate movement", "1");
        check(body, "Use longitudinal mode (whole recording)", true);
        check(body, "Show advanced settings before starting", false);
        message(body, "Longitudinal mode uses the fixed whole-recording route. Moving cells uses");
        message(body, "a benchmark-backed frame-to-frame recipe and therefore keeps longitudinal mode off.");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(new JButton("Cancel"));
        JButton start = new JButton("Start batch");
        start.setFont(start.getFont().deriveFont(Font.BOLD));
        buttons.add(start);
        JPanel root = new JPanel(new BorderLayout());
        root.add(new JScrollPane(body, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        root.setPreferredSize(new Dimension(690, 500));
        return dialog;
    }

    private static JDialog buildBatchProgress() {
        JDialog dialog = new JDialog((Frame) null, "Relative-Intensity Pattern Registration Batch", false);
        JPanel labels = new JPanel(new GridLayout(5, 1, 0, 5));
        labels.setBorder(new EmptyBorder(12, 12, 8, 12));
        labels.add(new JLabel("Current stack: experiment_07.ome.tif"));
        labels.add(new JLabel("Stacks: 6 / 24    Pairs: 43 / 79"));
        labels.add(new JLabel("Elapsed: 08:14"));
        labels.add(new JLabel("Estimated remaining: 24:37"));
        labels.add(new JLabel("Estimated finish: 15:42"));
        JPanel bottom = new JPanel(new BorderLayout(10, 0));
        JProgressBar progress = new JProgressBar(0, 1000);
        progress.setValue(273); progress.setStringPainted(true); progress.setString("27.3%");
        bottom.add(progress, BorderLayout.CENTER);
        bottom.add(new JButton("Cancel"), BorderLayout.EAST);
        bottom.setBorder(new EmptyBorder(0, 12, 12, 12));
        dialog.add(labels, BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);
        dialog.setPreferredSize(new Dimension(520, 230));
        return dialog;
    }

    private static JDialog buildSweepSetup() {
        JDialog dialog = new JDialog((Frame) null, "Parameter Sweep Setup", false);
        JPanel body = new JPanel(new GridBagLayout());
        body.setBorder(new EmptyBorder(12, 14, 8, 14));
        row = 0;
        message(body, "Choose up to three parameters and comma-separated values.");
        message(body, "The current stack is measured once for every combination; the source is unchanged.");
        choice(body, "Parameter 1", "Pixel removal strategy", "Preprocessing", "Estimation scale", "Robust weighting", "Pixel evidence",
                "Gradient multiplier", "Brightest pixels excluded (%)", "Dimmest pixels excluded (%)");
        wideField(body, "Values 1", "NONE, REMOVE_LEAST_INFORMATIVE, REMOVE_MOST_UNSTABLE");
        choice(body, "Parameter 2", "Eligible pixels removed (%)", "None", "Pixel-mask scoring filter", "Preprocessing", "Estimation scale");
        wideField(body, "Values 2", "10, 25, 50, 75");
        choice(body, "Parameter 3", "None", "Pixel-mask scoring filter", "Estimation scale", "Robust weighting");
        field(body, "Values 3", "");
        field(body, "Preview frame (1-based)", "48");
        message(body, "Maximum 24 combinations. Runs are sequential to keep memory bounded.");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(new JButton("Cancel"));
        JButton run = new JButton("Run sweep"); run.setFont(run.getFont().deriveFont(Font.BOLD));
        buttons.add(run);
        JPanel root = new JPanel(new BorderLayout());
        root.add(body, BorderLayout.CENTER); root.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(root); dialog.setPreferredSize(new Dimension(710, 440));
        return dialog;
    }

    private static JDialog buildSweepResults() {
        JDialog dialog = new JDialog((Frame) null, "Registration Parameter Sweep", false);
        JPanel header = new JPanel(new BorderLayout());
        header.add(new JLabel("  Red-cyan overlay: grey means the reference and corrected preview frame agree."),
                BorderLayout.NORTH);
        JProgressBar progress = new JProgressBar(0, 6); progress.setValue(6);
        progress.setStringPainted(true); progress.setString("6 / 6"); header.add(progress, BorderLayout.SOUTH);
        JPanel grid = new JPanel(new GridLayout(2, 3, 8, 8)); grid.setBorder(new EmptyBorder(8, 8, 8, 8));
        String[] scale = {"NONE", "LOW INFO 25%", "LOW INFO 25%", "MOST UNSTABLE 25%", "LOW INFO 50%", "LOW INFO 25%"};
        String[] preprocessing = {"NONE", "NONE", "ANSCOMBE", "NONE", "NONE", "MEDIAN_3X3"};
        double[] score = {0.0531, 0.0487, 0.0394, 0.0361, 0.0508, 0.0435};
        for (int i = 0; i < 6; i++) grid.add(sweepCard(i, scale[i], preprocessing[i], score[i], i == 3));
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(new JLabel("  Sweep complete in 31.4 s. Select the result you want to load."), BorderLayout.CENTER);
        JPanel buttons = new JPanel(); buttons.add(new JButton("Close"));
        JButton use = new JButton("Use selected settings"); use.setFont(use.getFont().deriveFont(Font.BOLD));
        buttons.add(use); footer.add(buttons, BorderLayout.EAST);
        dialog.add(header, BorderLayout.NORTH); dialog.add(grid, BorderLayout.CENTER); dialog.add(footer, BorderLayout.SOUTH);
        dialog.setPreferredSize(new Dimension(880, 650)); return dialog;
    }

    private static JPanel sweepCard(int index, String scale, String preprocessing, double score, boolean selected) {
        JPanel card = new JPanel(new BorderLayout(3, 3));
        JRadioButton radio = new JRadioButton("#" + (index + 1), selected);
        card.add(radio, BorderLayout.NORTH);
        JPanel preview = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                for (int y = 0; y < getHeight(); y += 5) for (int x = 0; x < getWidth(); x += 5) {
                    int v = (x * 3 + y * 5 + index * 17) & 127;
                    int red = Math.min(255, 70 + v + (selected ? 0 : 12));
                    int cyan = Math.min(255, 70 + v + (selected ? 0 : ((x / 5) % 3) * 10));
                    g.setColor(new Color(red, cyan, cyan)); g.fillRect(x, y, 5, 5);
                }
            }
        };
        preview.setPreferredSize(new Dimension(224, 168)); card.add(preview, BorderLayout.CENTER);
        card.add(new JLabel(String.format("<html><center>Pixel removal = %s<br>Mask filter = %s<br>full-size residual %.4f · %.1f s</center></html>",
                scale, preprocessing, score, 4.1 + index * .4), SwingConstants.CENTER), BorderLayout.SOUTH);
        card.setBorder(BorderFactory.createLineBorder(selected ? new Color(40,110,220) : new Color(180,180,180), selected ? 3 : 1));
        return card;
    }

    private static void section(JPanel panel, String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        label.setBorder(new EmptyBorder(8, 0, 2, 0));
        add(panel, label, 0, 2);
    }
    private static void message(JPanel p, String text) { add(p, new JLabel(text), 0, 2); }
    private static void field(JPanel p, String name, String value) {
        add(p, new JLabel(name), 0, 1);
        add(p, new JTextField(value, 17), 1, 1);
    }
    private static void wideField(JPanel p, String name, String value) {
        add(p, new JLabel(name), 0, 1);
        add(p, new JTextField(value, 40), 1, 1);
    }
    private static void choice(JPanel p, String name, String... values) {
        add(p, new JLabel(name), 0, 1);
        add(p, new JComboBox<String>(values), 1, 1);
    }
    private static void check(JPanel p, String text, boolean selected) {
        add(p, new JCheckBox(text, selected), 0, 2);
    }
    private static void add(JPanel panel, Component component, int x, int width) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x; c.gridy = row; c.gridwidth = width;
        c.anchor = GridBagConstraints.WEST; c.fill = width == 2 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        c.weightx = width == 2 ? 1 : (x == 1 ? 1 : 0);
        c.insets = new Insets(3, x == 1 ? 10 : 0, 3, 0);
        panel.add(component, c);
        if (x + width >= 2) row++;
    }
    private static void layout(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) if (child instanceof Container) layout((Container) child);
    }
}
