package com.freesia.system.iam.internal.origin;

import com.freesia.common.audit.ClientOrigin.Location;
import jakarta.annotation.PreDestroy;
import org.lionsoul.ip2region.xdb.Searcher;
import org.lionsoul.ip2region.xdb.Version;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

/** Bounded file-mode lookup. Two exclusive readers per family, shared index, no result cache. */
@Component
@EnableConfigurationProperties(LocationProperties.class)
public class OfflineLocations {
    private final Family ipv4;
    private final Family ipv6;
    public OfflineLocations(LocationProperties resources) {
        ipv4 = load(resources.ipv4(), Version.IPv4);
        ipv6 = load(resources.ipv6(), Version.IPv6);
    }
    public Location resolve(String literal) {
        try {
            byte[] address = InetAddress.ofLiteral(literal).getAddress();
            Family family = address.length == 4 ? ipv4 : ipv6;
            return family == null ? Location.unknown() : family.lookup(address);
        } catch (RuntimeException invalid) { return Location.unknown(); }
    }
    private static Family load(Resource resource, Version version) {
        if (resource == null || !resource.exists()) return null;
        Path extracted = null;
        var searchers = new ArrayList<Searcher>();
        try {
            Path path;
            if (resource.isFile()) path = resource.getFile().toPath();
            else {
                extracted = Files.createTempFile("freesia-ip-location-", ".xdb");
                try (var input = resource.getInputStream(); var output = Files.newOutputStream(extracted)) {
                    long bytes = 0;
                    byte[] buffer = new byte[8192];
                    for (int count; (count=input.read(buffer))!=-1;) {
                        bytes += count;
                        if (bytes > 128L * 1024 * 1024) throw new IOException("Location resource exceeds limit");
                        output.write(buffer,0,count);
                    }
                }
                path = extracted;
            }
            if (Files.size(path) > 128L * 1024 * 1024) throw new IOException("Location resource exceeds limit");
            Searcher.verifyFromFile(path.toFile());
            var header = Searcher.loadHeaderFromFile(path.toFile());
            if (header.version != 3 || header.ipVersion != version.id || header.runtimePtrBytes != 4) {
                throw new IOException("Unsupported location resource");
            }
            byte[] index = Searcher.loadVectorIndexFromFile(path.toFile());
            for (int i=0;i<2;i++) searchers.add(Searcher.newWithVectorIndex(version,path.toFile(),index));
            return new Family(searchers, extracted);
        } catch (Exception unavailable) {
            for (Searcher searcher : searchers) try { searcher.close(); } catch (IOException ignored) {}
            if (extracted != null) try { Files.deleteIfExists(extracted); } catch (IOException ignored) {}
            org.slf4j.LoggerFactory.getLogger(OfflineLocations.class).warn("Offline {} location data unavailable", version.name);
            return null;
        }
    }
    @PreDestroy
    public void close() {
        if (ipv4 != null) ipv4.close();
        if (ipv6 != null) ipv6.close();
    }
    private static final class Family {
        final ArrayBlockingQueue<Searcher> available = new ArrayBlockingQueue<>(2);
        final List<Searcher> readers;
        final Path extracted;
        boolean closing;
        Family(List<Searcher> readers, Path extracted) { this.readers=List.copyOf(readers); this.extracted=extracted; available.addAll(readers); }
        Location lookup(byte[] address) {
            Searcher reader;
            synchronized(this) { if (closing) return Location.unknown(); reader=available.poll(); }
            if (reader == null) return Location.unknown();
            try {
                String result = reader.search(address);
                if (result == null || result.length() > 1024) return Location.unknown();
                String[] parts = result.split("\\|",-1);
                if (parts.length != 5) return Location.unknown();
                return new Location(part(parts[0],120), part(parts[1],120), part(parts[2],120), part(parts[3],160), part(parts[4],8));
            } catch (Exception unreadable) { return Location.unknown(); }
            finally { synchronized(this) { available.offer(reader); notifyAll(); } }
        }
        private static String part(String value,int maximum) {
            return value.isBlank() || value.equals("0") ? null : value.substring(0,Math.min(value.length(),maximum));
        }
        synchronized void close() {
            closing=true;
            boolean interrupted=false;
            while(available.size()!=readers.size()) {
                try { wait(); } catch (InterruptedException failure) { interrupted=true; }
            }
            for (Searcher reader : readers) try { reader.close(); } catch (IOException ignored) {}
            if(extracted!=null) try { Files.deleteIfExists(extracted); } catch(IOException ignored) {}
            if(interrupted) Thread.currentThread().interrupt();
        }
    }
}
