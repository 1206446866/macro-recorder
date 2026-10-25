package com.macro.ui;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Path2D;
import java.util.*;

/** 只计算显示路径；目标身份始终由分支 ID 提供，不创建或改动流程节点。 */
final class TimelineBranchLayout {
    static final int RETRY_PORT_BOTTOM_OFFSET = 42;
    record Edge(String sourceId, String targetId, String label) { }
    record Route(Edge edge, List<Point> points, boolean forward, int laneY) { }
    private record Side(String nodeId, boolean right) { }

    /** 按实际分支 ID 与卡片边界计算下方绕行路径，为交叉连接分配端口和轨道，并保持出线与入箭头水平同高。 */
    static List<Route> routes(Map<String, Rectangle> cards, List<Edge> edges) {
        int sequenceY = cards.values().stream().mapToInt(r -> r.y + r.height - 70).max().orElse(0);
        return routes(cards, edges, sequenceY);
    }

    /** 按实际分支 ID 与卡片边界计算下方绕行路径，为交叉连接分配端口和轨道，并保持出线与入箭头水平同高。 */
    static List<Route> routes(Map<String, Rectangle> cards, List<Edge> edges, int sequenceY) {
        List<Edge> valid = edges.stream().filter(e -> !e.sourceId().equals(e.targetId())
                && cards.containsKey(e.sourceId()) && cards.containsKey(e.targetId())).toList();
        int bottom = cards.values().stream().mapToInt(r -> r.y + r.height).max().orElse(0);
        // 同一侧的出线和入线共同分配端口，反向连接也不会共用同一条竖线。
        Map<Side, Set<Integer>> occupied = new HashMap<>();
        List<Integer> ports = new ArrayList<>();
        for (Edge edge : valid) {
            boolean forward = cards.get(edge.targetId()).x > cards.get(edge.sourceId()).x;
            Set<Integer> sourcePorts = occupied.computeIfAbsent(new Side(edge.sourceId(), forward), k -> new HashSet<>());
            Set<Integer> targetPorts = occupied.computeIfAbsent(new Side(edge.targetId(), !forward), k -> new HashSet<>());
            int port = 0;
            while (sourcePorts.contains(port) || targetPorts.contains(port)) port++;
            sourcePorts.add(port);
            targetPorts.add(port);
            ports.add(port);
        }
        int maxPort = ports.stream().mapToInt(Integer::intValue).max().orElse(0);
        List<Route> result = new ArrayList<>();
        for (int lane = 0; lane < valid.size(); lane++) {
            Edge edge = valid.get(lane);
            Rectangle source = cards.get(edge.sourceId()), target = cards.get(edge.targetId());
            boolean forward = target.x > source.x;
            int direction = forward ? 1 : -1;
            int port = ports.get(lane);
            // 两端同高，端口限定在实际顺序线下方、重试端口上方，各留 8px。
            int retryY = Math.min(source.y + source.height, target.y + target.height) - RETRY_PORT_BOTTOM_OFFSET;
            int lower = retryY - 8;
            int upper = Math.min(lower, sequenceY + 8);
            double portSpacing = Math.min(8, (lower - upper) / (double) Math.max(1, maxPort));
            int entryY = maxPort == 0 ? (upper + lower) / 2 : lower - (int) Math.round(port * portSpacing);
            int entryX = forward ? target.x : target.x + target.width - 1;
            // 保持在已有连接器/轨道边距内，分离竖段时不侵入相邻卡片。
            int lead = 36 + 10 * port / Math.max(1, maxPort);
            int approachX = entryX - direction * lead;
            int startX = forward ? source.x + source.width - 1 : source.x;
            int sourceX = startX + direction * lead;
            int laneY = bottom + 94 + lane * 32;
            result.add(new Route(edge, List.of(new Point(startX, entryY), new Point(sourceX, entryY),
                    new Point(sourceX, laneY), new Point(approachX, laneY),
                    new Point(approachX, entryY), new Point(entryX, entryY)), forward, laneY));
        }
        return List.copyOf(result);
    }

    /** 只圆滑中间转角，保留发线和箭头入卡的水平直线。 */
    static Path2D path(Route route) {
        List<Point> points = route.points();
        Path2D path = new Path2D.Double();
        path.moveTo(points.get(0).x, points.get(0).y);
        for (int i = 1; i < points.size() - 1; i++) {
            Point previous = points.get(i - 1), corner = points.get(i), next = points.get(i + 1);
            double incoming = previous.distance(corner), outgoing = corner.distance(next);
            double radius = Math.min(12, Math.min(incoming, outgoing) / 2);
            if (radius == 0) { path.lineTo(corner.x, corner.y); continue; }
            path.lineTo(corner.x + (previous.x - corner.x) * radius / incoming,
                    corner.y + (previous.y - corner.y) * radius / incoming);
            path.quadTo(corner.x, corner.y,
                    corner.x + (next.x - corner.x) * radius / outgoing,
                    corner.y + (next.y - corner.y) * radius / outgoing);
        }
        Point end = points.get(points.size() - 1);
        path.lineTo(end.x, end.y);
        return path;
    }

    /** 为标签避开所有穿过该轨道的竖线，短连接也不让文字覆盖入箭头竖段。 */
    static Rectangle labelBounds(Route route, List<Route> routes, int textWidth, int availableWidth) {
        int width = textWidth + 20;
        TreeSet<Integer> barriers = new TreeSet<>(List.of(0, availableWidth));
        for (Route other : routes) for (int i = 1; i < other.points().size(); i++) {
            Point a = other.points().get(i - 1), b = other.points().get(i);
            if (a.x == b.x && Math.min(a.y, b.y) <= route.laneY() + 11
                    && Math.max(a.y, b.y) >= route.laneY() - 11) barriers.add(a.x);
        }
        int preferred = (route.points().get(2).x + route.points().get(3).x) / 2;
        int best = Math.max(5, preferred - width / 2), distance = Integer.MAX_VALUE;
        Integer previous = null;
        for (int barrier : barriers) {
            if (previous != null && barrier - previous >= width + 16) {
                int x = Math.max(previous + 8, Math.min(preferred - width / 2, barrier - 8 - width));
                int score = Math.abs(x + width / 2 - preferred);
                if (score < distance) { best = x; distance = score; }
            }
            previous = barrier;
        }
        return new Rectangle(best, route.laneY() - 11, width, 22);
    }
}
