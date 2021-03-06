package andrew.ren.springbootsample;

import java.time.Duration;
import java.util.Random;
import javax.annotation.PostConstruct; 
import org.apache.commons.lang.RandomStringUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;

import com.amazonaws.xray.AWSXRay;

@RestController
public class SampleController {

	@Value("${springbootsample.redis.host}")
    private String redis_host;
    
    @Value("${springbootsample.redis.port}")
    private int redis_port;
    
    @Value("${springbootsample.redis.connection}")
    private int redis_connection;
    
    private JedisPool pool;
    
    private String scriptsha = null;
    
    Logger logger = LoggerFactory.getLogger(SampleController.class);
    
    Random rand = new Random();
    
    @PostConstruct
    public void init() {
        
        final JedisPoolConfig poolConfig = new JedisPoolConfig();
	    poolConfig.setMaxTotal(redis_connection);
	    poolConfig.setMaxIdle(redis_connection);
	    poolConfig.setMinIdle(redis_connection/2);
	    poolConfig.setTestOnBorrow(true);
	    poolConfig.setTestOnReturn(true);
	    poolConfig.setTestWhileIdle(true);
	    poolConfig.setMinEvictableIdleTimeMillis(Duration.ofSeconds(60).toMillis());
	    poolConfig.setTimeBetweenEvictionRunsMillis(Duration.ofSeconds(30).toMillis());
	    poolConfig.setNumTestsPerEvictionRun(10);
	    poolConfig.setBlockWhenExhausted(true);
	    
        this.pool = new JedisPool(poolConfig, redis_host, redis_port);
        
    }
    
	@RequestMapping("/set")
	public String set(@RequestParam String id) {
		Jedis jedis = null;
		
        try{
            jedis = getRedisConnection();
	        AWSXRay.beginSubsegment("set");
            jedis.set(id, RandomStringUtils.random(15));
            AWSXRay.endSubsegment();
	        AWSXRay.beginSubsegment("append");
            jedis.append(id, RandomStringUtils.random(15));
            AWSXRay.endSubsegment();
	        AWSXRay.beginSubsegment("hset");
            jedis.hset(id+"hash", "name", RandomStringUtils.random(15));
            AWSXRay.endSubsegment();
	        AWSXRay.beginSubsegment("hset");
            jedis.hset(id+"hash", "address", RandomStringUtils.random(15));
            AWSXRay.endSubsegment();
	        AWSXRay.beginSubsegment("hset");
            jedis.hset(id+"hash", "number", RandomStringUtils.random(15));
            AWSXRay.endSubsegment();
	        AWSXRay.beginSubsegment("zdd");
            jedis.zadd("score_list", rand.nextDouble(), id);
            AWSXRay.endSubsegment();
        }finally{
            if(null != jedis)
                jedis.close();
        }
        return "OK";
	}
	
	@RequestMapping("/get")
	public String get(@RequestParam String id) {
		Jedis jedis = null;
		String result;
		
        try{
            jedis = getRedisConnection();
	        AWSXRay.beginSubsegment("get");
            result = jedis.get(id);
            AWSXRay.endSubsegment();
	        AWSXRay.beginSubsegment("hmget");
            jedis.hmget(id+"hash", "name", "address", "number", "nofield");
            AWSXRay.endSubsegment();
	        AWSXRay.beginSubsegment("zrem");
            jedis.zrem("score_list", id);
            AWSXRay.endSubsegment();
        }finally{
            if(null != jedis)
                jedis.close();
        }
        return result;
	}
	
	@RequestMapping("/scan")
	public String scan(@RequestParam int count) {
		Jedis jedis = null;
		String result;
		
        ScanParams scanParams = new ScanParams().count(count);
        String cursor = redis.clients.jedis.ScanParams.SCAN_POINTER_START; 
		
        try{
            jedis = getRedisConnection();
	        AWSXRay.beginSubsegment("scan");
            ScanResult<String> scanResult = jedis.scan(cursor, scanParams);
            result = scanResult.getResult().toString();
            AWSXRay.endSubsegment();
	        AWSXRay.beginSubsegment("zrange");
            result += jedis.zrange("score_list", 0, count).toString();
            AWSXRay.endSubsegment();
        }finally{
            if(null != jedis)
                jedis.close();
        }
        return result;
	}
	
	@RequestMapping("/lua")
	public String lua() {
		Jedis jedis = null;
		String result;
		
        try{
            jedis = getRedisConnection();
            if (scriptsha == null)
                scriptsha = this.loadscript(jedis);
	        AWSXRay.beginSubsegment("evalsha");
            result = jedis.evalsha(scriptsha).toString();
            AWSXRay.endSubsegment();
        }finally{
            if(null != jedis)
                jedis.close();
        }
        return result;
	}

    @RequestMapping("/singleset")
	public String singleset(@RequestParam String id) {
	    Jedis jedis = null;
		String result;
		
		jedis = new Jedis(redis_host, redis_port);
		jedis.set(id, RandomStringUtils.random(15));
	    AWSXRay.beginSubsegment("get");
		result = jedis.get(id);
        AWSXRay.endSubsegment();
		
        return result;
	}
	
	@RequestMapping("/")
	public String health() {
	    return "OK";
	}
	
	private String loadscript(Jedis jedis) {
	    String result;
	    
        String script = "local rank = redis.call('zrangebyscore', 'score_list', '-inf', '+inf'); local count = 0; local result=''; " +
                        "for k, v in pairs(rank) do " +
                        "  if (count < 10) then " +
                        "    local entry = redis.call('hmget', v .. 'hash', 'name', 'address', 'number') " +
                        "    result = result .. table.concat(entry, ' ') " +
                        "    count = count + 1 " +
                        "  end " +
                        "end " +
                        "return result ";
	    AWSXRay.beginSubsegment("scriptload");
        result = jedis.scriptLoad(script);
        AWSXRay.endSubsegment();
        
        return result;
	}
	
	private Jedis getRedisConnection() {
	    AWSXRay.beginSubsegment("getpoolresource");
        Jedis jedis = pool.getResource();
        AWSXRay.endSubsegment();
        return jedis;
	}
}