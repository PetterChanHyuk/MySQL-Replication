package replication.config;

import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import com.zaxxer.hikari.HikariDataSource;

@Configuration
public class DataSourceConfig {

	// ── Source DB (쓰기) ───────────────────────────────
	@Bean("sourceDataSource")
	@ConfigurationProperties(prefix = "spring.datasource.source")
	public DataSource sourceDataSource() {
		return new HikariDataSource();
	}

	// ── Replica DB (읽기) ──────────────────────────────
	@Bean("replicaDataSource")
	@ConfigurationProperties(prefix = "spring.datasource.replica")
	public DataSource replicaDataSource() {
		return new HikariDataSource();
	}

	// ── RoutingDataSource: readOnly 여부로 분기 ─────────
	@Bean("routingDataSource")
	public DataSource routingDataSource(@Qualifier("sourceDataSource") DataSource source,
			@Qualifier("replicaDataSource") DataSource replica) {
		Map<Object, Object> targets = new HashMap<>();
		targets.put(RoutingDataSource.SOURCE, source);
		targets.put(RoutingDataSource.REPLICA, replica);

		RoutingDataSource routing = new RoutingDataSource();
		routing.setTargetDataSources(targets);
		routing.setDefaultTargetDataSource(source); // 트랜잭션 없을 땐 Source
		routing.afterPropertiesSet();
		return routing;
	}

	/**
	 * LazyConnectionDataSourceProxy로 감싸는 이유: Spring은 트랜잭션 시작 시 즉시 커넥션을 가져오려 한다. 이
	 * 시점엔 아직 readOnly 여부가 결정되지 않아 항상 Source로 가게 된다. Lazy로 감싸면 실제 쿼리 실행 직전에 커넥션을 획득
	 * → RoutingDataSource가 readOnly를 올바르게 읽을 수 있다.
	 */
	@Bean
	@Primary
	public DataSource dataSource(@Qualifier("routingDataSource") DataSource routing) {
		return new LazyConnectionDataSourceProxy(routing);
	}
}
