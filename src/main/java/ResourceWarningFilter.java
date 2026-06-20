import java.io.CharArrayWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.text.DecimalFormat;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

/**
 * HTML 응답에 시스템 자원 사용률 경고 배너를 자동으로 삽입하는 필터입니다.
 */
public class ResourceWarningFilter implements Filter {
    private static final double RESOURCE_WARNING_THRESHOLD = 90.0d;
    private static final DecimalFormat RESOURCE_USAGE_FORMAT = new DecimalFormat("0.0");

    public void init(FilterConfig filterConfig) throws ServletException {
    }

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        if (!isHtmlRequest(httpRequest)) {
            chain.doFilter(request, response);
            return;
        }

        ResourceWarningResponseWrapper responseWrapper = new ResourceWarningResponseWrapper((HttpServletResponse) response);
        chain.doFilter(request, responseWrapper);

        String body = responseWrapper.getCapturedResponse();
        String contentType = responseWrapper.getContentType();
        ResourceUsageStatus usageStatus = getResourceUsageStatus();

        if (isHtmlResponse(contentType) && usageStatus.isWarning()) {
            body = insertWarningBanner(body, usageStatus);
        }

        response.getWriter().write(body);
    }

    public void destroy() {
    }

    private static boolean isHtmlRequest(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        return requestUri == null
            || requestUri.endsWith("/")
            || requestUri.endsWith(".jsp")
            || requestUri.endsWith(".html")
            || requestUri.endsWith(".htm");
    }

    private static boolean isHtmlResponse(String contentType) {
        return contentType == null || contentType.toLowerCase().indexOf("text/html") >= 0;
    }

    private static ResourceUsageStatus getResourceUsageStatus() {
        double highestUsagePercent = -1.0d;
        String highestUsageName = "";

        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        if (heapUsage != null && heapUsage.getMax() > 0) {
            double heapUsagePercent = ((double) heapUsage.getUsed() / (double) heapUsage.getMax()) * 100.0d;
            highestUsagePercent = heapUsagePercent;
            highestUsageName = "JVM 힙 메모리";
        }

        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOsBean = (com.sun.management.OperatingSystemMXBean) osBean;
            long totalPhysicalMemory = sunOsBean.getTotalPhysicalMemorySize();
            long freePhysicalMemory = sunOsBean.getFreePhysicalMemorySize();
            if (totalPhysicalMemory > 0) {
                double systemMemoryUsagePercent = ((double) (totalPhysicalMemory - freePhysicalMemory) / (double) totalPhysicalMemory) * 100.0d;
                if (systemMemoryUsagePercent > highestUsagePercent) {
                    highestUsagePercent = systemMemoryUsagePercent;
                    highestUsageName = "시스템 메모리";
                }
            }
        }

        double systemCpuUsagePercent = getSystemCpuUsagePercent(osBean);
        if (systemCpuUsagePercent > highestUsagePercent) {
            highestUsagePercent = systemCpuUsagePercent;
            highestUsageName = "시스템 CPU";
        }

        return new ResourceUsageStatus(highestUsageName, highestUsagePercent);
    }

    private static double getSystemCpuUsagePercent(OperatingSystemMXBean osBean) {
        // CPU 사용률은 WAS/JDK 버전에 따라 제공 여부가 달라 리플렉션으로 안전하게 확인합니다.
        try {
            Method systemCpuLoadMethod = osBean.getClass().getMethod("getSystemCpuLoad", new Class[0]);
            Object systemCpuLoadValue = systemCpuLoadMethod.invoke(osBean, new Object[0]);
            if (systemCpuLoadValue instanceof Number) {
                double systemCpuLoad = ((Number) systemCpuLoadValue).doubleValue();
                if (systemCpuLoad >= 0.0d) {
                    return systemCpuLoad * 100.0d;
                }
            }
        } catch (Exception cpuLoadException) {
            double systemLoadAverage = osBean.getSystemLoadAverage();
            int availableProcessors = osBean.getAvailableProcessors();
            if (systemLoadAverage >= 0.0d && availableProcessors > 0) {
                return Math.min((systemLoadAverage / (double) availableProcessors) * 100.0d, 100.0d);
            }
        }
        return -1.0d;
    }

    private static String insertWarningBanner(String body, ResourceUsageStatus usageStatus) {
        String banner = buildWarningBanner(usageStatus);
        String lowerBody = body.toLowerCase();
        int bodyStartIndex = lowerBody.indexOf("<body");
        if (bodyStartIndex < 0) {
            return banner + body;
        }

        int bodyTagEndIndex = lowerBody.indexOf(">", bodyStartIndex);
        if (bodyTagEndIndex < 0) {
            return banner + body;
        }

        return body.substring(0, bodyTagEndIndex + 1) + banner + body.substring(bodyTagEndIndex + 1);
    }

    private static String buildWarningBanner(ResourceUsageStatus usageStatus) {
        String usagePercent = RESOURCE_USAGE_FORMAT.format(usageStatus.getUsagePercent());
        String usageName = escapeHtml(usageStatus.getUsageName());
        return "<style type=\"text/css\">"
            + ".resource-warning{margin:12px 0;padding:14px 16px;border:1px solid #d97706;"
            + "border-left:6px solid #d97706;border-radius:4px;background:#fff7ed;color:#7c2d12;"
            + "font-weight:bold;line-height:1.5;}"
            + "</style>"
            + "<div class=\"resource-warning\" role=\"alert\">"
            + "현재 " + usageName + " 사용률이 " + usagePercent + "%입니다. "
            + "JVM 힙/시스템 메모리/시스템 CPU 중 하나가 90% 이상 사용 중이므로 사이트가 느릴 수 있습니다."
            + "</div>";
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static class ResourceUsageStatus {
        private final String usageName;
        private final double usagePercent;

        ResourceUsageStatus(String usageName, double usagePercent) {
            this.usageName = usageName;
            this.usagePercent = usagePercent;
        }

        String getUsageName() {
            return usageName;
        }

        double getUsagePercent() {
            return usagePercent;
        }

        boolean isWarning() {
            return usagePercent >= RESOURCE_WARNING_THRESHOLD;
        }
    }

    private static class ResourceWarningResponseWrapper extends HttpServletResponseWrapper {
        private final CharArrayWriter captureWriter = new CharArrayWriter();
        private final PrintWriter writer = new PrintWriter(captureWriter);

        ResourceWarningResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        public PrintWriter getWriter() throws IOException {
            return writer;
        }

        public ServletOutputStream getOutputStream() throws IOException {
            throw new IllegalStateException("ResourceWarningFilter는 문자 기반 HTML 응답만 처리합니다.");
        }

        String getCapturedResponse() {
            writer.flush();
            return captureWriter.toString();
        }
    }
}
