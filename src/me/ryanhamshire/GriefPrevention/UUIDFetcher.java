//BIG THANKS to EvilMidget38 for providing this handy UUID lookup tool to the Bukkit community!  :)

package me.ryanhamshire.GriefPrevention;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;

import org.bukkit.OfflinePlayer;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
 
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Callable;
 
class UUIDFetcher {
    private static final double PROFILES_PER_REQUEST = 100;
    private static final String PROFILE_URL = "https://api.mojang.com/profiles/minecraft";
    private final JSONParser jsonParser = new JSONParser();
    private final List<String> names;
    private final boolean rateLimiting;
    
    //cache for username -> uuid lookups
    private static HashMap<String, UUID> lookupCache;
 
    public UUIDFetcher(List<String> names, boolean rateLimiting) {
        this.names = names;
        this.rateLimiting = rateLimiting;
    }
 
    public UUIDFetcher(List<String> names) {
        this(names, true);
    }
 
    public void call() throws Exception
    {
        if(lookupCache == null)
        {
            lookupCache = new HashMap<String, UUID>();
        }
        
        GriefPrevention.AddLogEntry("UUID conversion process started.  Please be patient - this may take a while.");
        
        //try to get correct casing from local data
        OfflinePlayer [] players = GriefPrevention.instance.getServer().getOfflinePlayers();
        GriefPrevention.AddLogEntry("Checking local server data to get correct casing for player names...");
        for(int i = 0; i < names.size(); i++)
        {
            String name = names.get(i);
            for(OfflinePlayer player : players)
            {
                if(player.getName().equalsIgnoreCase(name))
                {
                    if(!player.getName().equals(name))
                    {
                        GriefPrevention.AddLogEntry(name + " --> " + player.getName());
                        names.set(i,  player.getName()); 
                        continue;
                    }
                }
            }
        }
        
        //look for local data first
        GriefPrevention.AddLogEntry("Checking local server data for UUIDs already seen...");
        for(int i = 0; i < names.size(); i++)
        {
            String name = names.get(i);
            for(OfflinePlayer player : players)
            {
                if(player.getName().equalsIgnoreCase(name))
                {
                    UUID uuid = player.getUniqueId();
                    if(uuid != null)
                    {
                        GriefPrevention.AddLogEntry(name + " --> " + uuid.toString());
                        lookupCache.put(name, uuid);
                        lookupCache.put(name.toLowerCase(), uuid);
                        names.remove(i--);
                        continue;
                    }
                }
            }
        }
        
        //for online mode, call Mojang to resolve the rest
        if(GriefPrevention.instance.getServer().getOnlineMode())
        {
            GriefPrevention.AddLogEntry("Calling Mojang to get UUIDs for remaining unresolved players (this is the slowest step)...");
            
            int requests = (int) Math.ceil(names.size() / PROFILES_PER_REQUEST);
            for (int i = 0; i < requests; i++)
            {
                HttpURLConnection connection = createConnection();
                String body = JSONArray.toJSONString(names.subList(i * 100, Math.min((i + 1) * 100, names.size())));
                writeBody(connection, body);
                JSONArray array = (JSONArray) jsonParser.parse(new InputStreamReader(connection.getInputStream()));
                for (Object profile : array) {
                    JSONObject jsonProfile = (JSONObject) profile;
                    String id = (String) jsonProfile.get("id");
                    String name = (String) jsonProfile.get("name");
                    UUID uuid = UUIDFetcher.getUUID(id);
                    GriefPrevention.AddLogEntry(name + " --> " + uuid.toString());
                    lookupCache.put(name, uuid);
                    lookupCache.put(name.toLowerCase(), uuid);
                }
                if (rateLimiting && i != requests - 1) {
                    Thread.sleep(200L);
                }
            }
        }
        
        //for offline mode, generate UUIDs for the rest
        else
        {
            GriefPrevention.AddLogEntry("Generating offline mode UUIDs for remaining unresolved players...");
            
            for(int i = 0; i < names.size(); i++)
            {
                String name = names.get(i);
                UUID uuid = java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(Charsets.UTF_8));
                GriefPrevention.AddLogEntry(name + " --> " + uuid.toString());
                lookupCache.put(name, uuid);
                lookupCache.put(name.toLowerCase(), uuid);
            }
        }
    }
 
    private static void writeBody(HttpURLConnection connection, String body) throws Exception {
        OutputStream stream = connection.getOutputStream();
        stream.write(body.getBytes());
        stream.flush();
        stream.close();
    }
 
    private static HttpURLConnection createConnection() throws Exception {
        URL url = new URL(PROFILE_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setUseCaches(false);
        connection.setDoInput(true);
        connection.setDoOutput(true);
        return connection;
    }
 
    private static UUID getUUID(String id) {
        return UUID.fromString(id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16) + "-" + id.substring(16, 20) + "-" +id.substring(20, 32));
    }
 
    public static byte[] toBytes(UUID uuid) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(new byte[16]);
        byteBuffer.putLong(uuid.getMostSignificantBits());
        byteBuffer.putLong(uuid.getLeastSignificantBits());
        return byteBuffer.array();
    }
 
    public static UUID fromBytes(byte[] array) {
        if (array.length != 16) {
            throw new IllegalArgumentException("Illegal byte array length: " + array.length);
        }
        ByteBuffer byteBuffer = ByteBuffer.wrap(array);
        long mostSignificant = byteBuffer.getLong();
        long leastSignificant = byteBuffer.getLong();
        return new UUID(mostSignificant, leastSignificant);
    }
 
    public static UUID getUUIDOf(String name) throws Exception
    {
        UUID result = lookupCache.get(name);
        if(result == null)
        {
            //throw up our hands and report the problem in the logs
            //this player will lose his land claim blocks, but claims will stay in place as admin claims
            throw new IllegalArgumentException(name);
        }
        
        return result;
    }
}