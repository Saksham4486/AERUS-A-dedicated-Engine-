package com.aerus.cli;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JPanel;

public class GraphPanel extends JPanel {

    private final List<Integer> dataPoints = new ArrayList<>();
    private final int maxDataPoints = 50;
    private final String title;
    private final Color lineColor;

    public GraphPanel(String title, Color lineColor) {
        this.title = title;
        this.lineColor = lineColor;
        setPreferredSize(new Dimension(300, 150));
        setBackground(Color.DARK_GRAY);
    }

    public void addData(int value) {
        dataPoints.add(value);
        if (dataPoints.size() > maxDataPoints) {
            dataPoints.remove(0);
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int width = getWidth();
        int height = getHeight();

        // Draw title
        g2.setColor(Color.WHITE);
        g2.drawString(title, 10, 20);

        if (dataPoints.isEmpty()) {
            return;
        }

        // Calculate max for scaling
        int max = 1;
        for (int v : dataPoints) {
            if (v > max) max = v;
        }

        // Draw grid
        g2.setColor(Color.GRAY);
        g2.drawLine(20, height - 20, width - 10, height - 20); // X-axis
        g2.drawLine(20, 30, 20, height - 20); // Y-axis

        // Draw line graph
        g2.setColor(lineColor);
        g2.setStroke(new BasicStroke(2f));
        
        int xStep = (width - 40) / maxDataPoints;
        int prevX = -1, prevY = -1;

        for (int i = 0; i < dataPoints.size(); i++) {
            int value = dataPoints.get(i);
            int x = 20 + i * xStep;
            int y = (height - 20) - (int) (((double) value / max) * (height - 50));

            if (prevX != -1) {
                g2.drawLine(prevX, prevY, x, y);
            }
            g2.fillOval(x - 3, y - 3, 6, 6);
            
            prevX = x;
            prevY = y;
        }
    }
}

