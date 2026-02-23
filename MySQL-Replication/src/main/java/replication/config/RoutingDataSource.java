package replication.config;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class RoutingDataSource extends AbstractRoutingDataSource {

	public static final String SOURCE = "source";
	public static final String REPLICA = "replica";

	/**
	 * @Transactional(readOnly = true) 이면 → Replica
	 * @Transactional 이면 → Source
	 *
	 *                Spring이 트랜잭션 시작 시 readOnly 여부를
	 *                TransactionSynchronizationManager에 등록하고, 첫 커넥션 획득 전에 이 메서드가
	 *                호출되어 DataSource를 결정한다.
	 */
	@Override
	protected Object determineCurrentLookupKey() {
		boolean isReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
		String target = isReadOnly ? REPLICA : SOURCE;

		System.out.printf("[RoutingDataSource] readOnly=%b → %s DB%n", isReadOnly, target.toUpperCase());

		return target;
	}
}
