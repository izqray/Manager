package com.springbatch.pcf;


import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.batch.integration.chunk.RemoteChunkingManagerStepBuilderFactory;
import org.springframework.batch.integration.config.annotation.EnableBatchIntegration;
import org.springframework.batch.item.support.ListItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.amqp.dsl.Amqp;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;

import java.util.Arrays;

import static config.RabbitConstants.REPLY_QUEUE_NAME;
import static config.RabbitConstants.REQUEST_QUEUE_NAME;

/**
 * This configuration class is for the manager side of the remote chunking sample.
 * The manager step reads numbers from 1 to 6 and sends 2 chunks {1, 2, 3} and
 * {4, 5, 6} to workers for processing and writing.
 *
 * @author Mahmoud Ben Hassine
 */
@Configuration
@EnableBatchProcessing
@EnableBatchIntegration
public class ManagerConfiguration {

    @Autowired
    private JobBuilderFactory jobBuilderFactory;

    @Autowired
    private RemoteChunkingManagerStepBuilderFactory managerStepBuilderFactory;

    @Bean
    public ConnectionFactory connectionFactory() {
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory("localhost");
        connectionFactory.setPort(5672);
        connectionFactory.setUsername("guest");
        connectionFactory.setPassword("guest");
        return connectionFactory;
    }

    @Bean
    public RabbitTemplate worksRabbitTemplate() {
        RabbitTemplate r = new RabbitTemplate(connectionFactory());
        r.setExchange("direct-exchange");
        r.setRoutingKey(REQUEST_QUEUE_NAME);
        r.setConnectionFactory(connectionFactory());
        return r;
    }

    @Bean
    public DirectChannel requests() {
        return new DirectChannel();
    }

    @Bean
    public IntegrationFlow outboundFlow() {
        return IntegrationFlows
                .from("requests")
                .handle(Amqp.outboundAdapter(worksRabbitTemplate()).routingKey(REQUEST_QUEUE_NAME))
                .get();
    }

    /*
     * Configure inbound flow (replies coming from workers)
     */
    @Bean
    public QueueChannel replies() {
        return new QueueChannel();
    }

    @Bean
    public SimpleMessageListenerContainer managerListenerContainer() {
        SimpleMessageListenerContainer container =
                new SimpleMessageListenerContainer(connectionFactory());
        container.setQueueNames(REPLY_QUEUE_NAME);
        container.setConcurrentConsumers(2);
        container.setDefaultRequeueRejected(false);
//        container.setAdviceChain(new Advice[]{interceptor()});
        return container;
    }

    @Bean
    public IntegrationFlow managerInboundFlow() {
        return IntegrationFlows
                .from(Amqp.inboundAdapter(managerListenerContainer()).get())
                .channel(replies())
                .get();
    }

    /*
     * Configure master step components
     */
    @Bean
    public ListItemReader<Integer> itemReader() {
        return new ListItemReader<>(Arrays.asList(1, 2, 3, 4, 5, 6));
    }

    @Bean
    public TaskletStep managerStep() {
        return this.managerStepBuilderFactory.get("managerStep")
                .<Integer, Integer>chunk(3)
                .reader(itemReader())
                .outputChannel(requests())
                .inputChannel(replies())
                .build();
    }

    @Bean
    public Job remoteChunkingJob() {
        return this.jobBuilderFactory.get("remoteChunkingJob")
                .start(managerStep())
                .build();
    }

}