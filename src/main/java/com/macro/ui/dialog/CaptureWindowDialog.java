package com.macro.ui.dialog;

import com.macro.constant.UiConstants;
import com.macro.ui.capture.CaptureWindowService;
import com.macro.ui.theme.ThemeManager;
import com.macro.ui.util.ComponentStyleUtil;
import com.macro.ui.window.WindowTitleBarSupport;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/** 截图专用窗口列表；选择不会激活窗口、注册全局钩子或改动录制目标。 */
public final class CaptureWindowDialog extends JDialog {
    private final CaptureWindowService service;
    private final WindowTitleBarSupport titleBarSupport;
    private final DefaultListModel<CaptureWindowService.Target> model = new DefaultListModel<>();
    private final JList<CaptureWindowService.Target> windows = new JList<>(model);
    private final JLabel status = new JLabel("正在读取窗口列表…");
    private final JButton refresh = new JButton("刷新列表"), confirm = new JButton("选择窗口");
    private SwingWorker<List<CaptureWindowService.Target>, Void> worker;
    private CaptureWindowService.Target selected;
    private boolean closed;

    public CaptureWindowDialog(Window owner) { this(owner, new CaptureWindowService()); }

    public CaptureWindowDialog(Window owner, String title, String hint) {
        this(owner, new CaptureWindowService(), title, hint);
    }

    CaptureWindowDialog(Window owner, CaptureWindowService service) {
        this(owner, service, "指定截图窗口", "选择后返回采集页，点击“截图”时才会前置窗口并高亮内容区");
    }

    private CaptureWindowDialog(Window owner, CaptureWindowService service, String title, String description) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        this.service = service;
        var theme = ThemeManager.getPalette();
        Font font = new Font(UiConstants.DEFAULT_FONT_FAMILY, Font.PLAIN, 13);
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(new EmptyBorder(16, 18, 16, 18)); content.setBackground(theme.background());
        JLabel hint = new JLabel(description);
        hint.setForeground(theme.textSecondary()); hint.setFont(font); content.add(hint, BorderLayout.NORTH);
        windows.setFont(font); windows.setFixedCellHeight(34); windows.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        windows.setBackground(theme.card()); windows.setForeground(theme.textPrimary());
        windows.setSelectionBackground(theme.accent()); windows.setSelectionForeground(theme.textOnAccent());
        windows.addListSelectionListener(event -> confirm.setEnabled(!closed && worker == null && windows.getSelectedValue() != null));
        windows.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                int index = windows.locationToIndex(event.getPoint());
                if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 2 && index >= 0
                        && windows.getCellBounds(index, index).contains(event.getPoint())) accept();
            }
        });
        content.add(new JScrollPane(windows), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(0, 10)); bottom.setOpaque(false);
        status.setFont(font); status.setForeground(theme.textSecondary()); bottom.add(status, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); buttons.setOpaque(false);
        JButton cancel = new JButton("取消"); cancel.addActionListener(event -> dispose());
        for (JButton button : new JButton[]{refresh, cancel, confirm}) {
            button.setFont(font); ComponentStyleUtil.styleSecondaryButton(button); buttons.add(button);
        }
        ComponentStyleUtil.stylePrimaryButton(confirm);
        refresh.addActionListener(event -> reload()); confirm.addActionListener(event -> accept());
        bottom.add(buttons, BorderLayout.SOUTH); content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content); setSize(700, 460); setMinimumSize(new Dimension(620, 360)); setLocationRelativeTo(owner);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getRootPane().setDefaultButton(confirm);
        getRootPane().registerKeyboardAction(event -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        titleBarSupport = new WindowTitleBarSupport(this);
        reload();
    }

    public Optional<CaptureWindowService.Target> getSelection() { return Optional.ofNullable(selected); }

    private void reload() {
        if (closed || worker != null) return;
        CaptureWindowService.Target previous = windows.getSelectedValue();
        refresh.setEnabled(false); confirm.setEnabled(false); windows.setEnabled(false);
        status.setText("正在读取窗口列表…"); status.setToolTipText(null);
        worker = new SwingWorker<>() {
            @Override protected List<CaptureWindowService.Target> doInBackground() throws Exception { return service.listWindows(); }
            @Override protected void done() {
                try {
                    if (closed) return;
                    List<CaptureWindowService.Target> targets = get(); model.clear(); targets.forEach(model::addElement);
                    if (previous != null) {
                        for (int index = 0; index < model.size(); index++) {
                            var candidate = model.get(index);
                            if (candidate.handle() == previous.handle() && candidate.processId() == previous.processId()) windows.setSelectedIndex(index);
                        }
                    }
                    status.setText(targets.isEmpty() ? "未找到可截图窗口，请打开目标应用后刷新" : "找到 " + targets.size() + " 个窗口；列表不包含本程序窗口");
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                catch (CancellationException ignored) { }
                catch (ExecutionException failed) {
                    model.clear(); status.setText("读取失败，请刷新重试"); status.setToolTipText(failed.getCause().getMessage());
                } finally {
                    worker = null;
                    if (!closed) { refresh.setEnabled(true); windows.setEnabled(true); confirm.setEnabled(windows.getSelectedValue() != null); }
                }
            }
        };
        worker.execute();
    }
    private void accept() {
        if (closed || worker != null || windows.getSelectedValue() == null) return;
        selected = windows.getSelectedValue(); dispose();
    }
    @Override public void dispose() {
        closed = true;
        if (worker != null) worker.cancel(true);
        super.dispose();
    }
    @Override public void addNotify() { super.addNotify(); if (titleBarSupport != null) titleBarSupport.activate(); }
    @Override public void removeNotify() {
        try { if (titleBarSupport != null) titleBarSupport.deactivate(); } finally { super.removeNotify(); }
    }
}
