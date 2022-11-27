package product.service.product;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import product.dto.product.ProductDetailResponseDto;
import product.dto.product.ProductMainResponseDto;
import product.entity.product.Product;
import product.repository.product.ProductRepository;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@RequiredArgsConstructor
@Service
@Slf4j
public class ProductRedisService {
    private final RedisTemplate<String, String> redisTemplate;
    private final RedisTemplate<String, ProductDetailResponseDto> redisTemplateDetailDto;
    private final RedisTemplate<String, ProductMainResponseDto> redisTemplateMainDto;
    private final ProductRepository productRepository;


    // Named Post PipeLine
    public void warmupPipeLine(List<ProductDetailResponseDto> list) {
        RedisSerializer keySerializer = redisTemplate.getStringSerializer();
        RedisSerializer valueSerializer = redisTemplate.getValueSerializer();

        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            list.forEach(i -> {
                String key = "Product::"+i.getProductId();
                connection.listCommands().rPush(keySerializer.serialize(key),
                        valueSerializer.serialize(i));
            });
            return null;
        });
    }

    // Ranking Board PipeLine
    public void warmupRankingPipeLine(List<Product> list, Long categoryId){
        RedisSerializer keySerializer = redisTemplate.getStringSerializer();
        RedisSerializer valueSerializer = redisTemplate.getValueSerializer();

        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            list.forEach(i -> {
                connection.zSetCommands().zAdd(keySerializer.serialize("Ranking::"+categoryId),
                        i.getView(), valueSerializer.serialize(ProductMainResponseDto.toDto(i)));
            });
            return null;
        });
    }

    // 랭킹보드 조회
    public Set<ZSetOperations.TypedTuple<ProductMainResponseDto>> getRankingBoard(String key) {
        ZSetOperations<String, ProductMainResponseDto> ZSetOperations = redisTemplateMainDto.opsForZSet();
        return ZSetOperations.reverseRangeWithScores(key, 0, 99);
    }

    // 상품 조회수 Key-Value Setting
    public void setView(String key, String data, Duration duration) {
        ValueOperations<String, String> values = redisTemplate.opsForValue();
        values.set(key, data, duration);
    }

    // 상품 조회수 증가
    public void incrementView(String key, Long productId) {
        ValueOperations<String, String> values = redisTemplate.opsForValue();
        // key : [productView::1] -> value : [1]
        if(values.get(key) == null) {
            setView(key, String.valueOf(productRepository.getView(productId)), Duration.ofMinutes(35));
            values.increment(key);
        }
        else values.increment(key);
    }

    // 상품 조회수 DB Update
    @Scheduled(cron = "0 0/100 * * * ?")
    @Transactional
    public void UpdateViewRDS() {
        Set<String> redisKeys = redisTemplate.keys("productView*");

        log.info("Starting View Update !");

        if (redisKeys == null) return;

        for (String data : redisKeys) {
            Long productId = Long.parseLong(data.split("::")[1]);
            int viewCnt = Integer.parseInt(Objects.requireNonNull(redisTemplate.opsForValue().get(data)));

            productRepository.addView(productId, viewCnt);

            redisTemplate.delete(data);
        }
        log.info("Update View !");
    }

    // 상품 상세페이지 캐싱 -> NonePipeLine
    public void setProduct(String key, ProductDetailResponseDto data, Duration duration) {
        ValueOperations<String, ProductDetailResponseDto> values = redisTemplateDetailDto.opsForValue();
        values.set(key, data, duration);
    }

    // 랭킹보드 캐싱 -> NonePipeLine
    public void setRankingBoard(String key, ProductMainResponseDto data, double score) {
        ZSetOperations<String, ProductMainResponseDto> values = redisTemplateMainDto.opsForZSet();
        values.add(key, data, score);
    }
}


