/*
 * Copyright (c) 2013, OpenCloudDB/MyCAT and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software;Designed and Developed mainly by many Chinese 
 * opensource volunteers. you can redistribute it and/or modify it under the 
 * terms of the GNU General Public License version 2 only, as published by the
 * Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 * 
 * Any questions about this component can be directed to it's project Web address 
 * https://code.google.com/p/opencloudb/.
 *
 */
package org.opencloudb.route.function;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.SortedMap;
import java.util.TreeMap;

import org.opencloudb.config.model.rule.RuleAlgorithm;
import org.opencloudb.exception.MurmurHashException;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

/**
 * consistancy hash, murmur hash
 * implemented by Guava
 * @author wuzhih
 *
 */
public class PartitionByMurmurHash extends AbstractPartitionAlgorithm implements RuleAlgorithm  {
	private static final int DEFAULT_VIRTUAL_BUCKET_TIMES=160;
	private static final int DEFAULT_WEIGHT=1;
	private static final Charset DEFAULT_CHARSET=Charset.forName("UTF-8");
	
	private int seed;
	private int count;
	private int virtualBucketTimes=DEFAULT_VIRTUAL_BUCKET_TIMES;
	private Map<Integer,Integer> weightMap=new HashMap<>();
	private String bucketMapPath;
	
	private HashFunction hash;
	
	private SortedMap<Integer,Integer> bucketMap;
	@Override
	public void init()  {
		try{
			bucketMap=new TreeMap<>();
			boolean serializableBucketMap=bucketMapPath!=null && bucketMapPath.length()>0;
			if(serializableBucketMap){
				File bucketMapFile=new File(bucketMapPath);
				if(bucketMapFile.exists() && bucketMapFile.length()>0){
					loadBucketMapFile();
					return;
				}
			}
			generateBucketMap();
			if(serializableBucketMap){
				storeBucketMap();
			}
		}catch(Exception e){
			throw new MurmurHashException(e);
		}
	}

	private void generateBucketMap(){
		hash=Hashing.murmur3_32(seed);//计算一致性哈希的对象
		for(int i=0;i<count;i++){//构造一致性哈希环，用TreeMap表示
			StringBuilder hashName=new StringBuilder("SHARD-").append(i);
			for(int n=0,shard=virtualBucketTimes*getWeight(i);n<shard;n++){
				bucketMap.put(hash.hashUnencodedChars(hashName.append("-NODE-").append(n)).asInt(),i);
			}
		}
		weightMap=null;
	}
	private void storeBucketMap() throws FileNotFoundException{
		try(PrintStream store=new PrintStream(new FileOutputStream(bucketMapPath))){
			Properties props=new Properties();
			for(Map.Entry entry:bucketMap.entrySet()){
				props.setProperty(entry.getKey().toString(), entry.getValue().toString());
			}
			props.list(store);
		}
	}
	private void loadBucketMapFile() throws FileNotFoundException, IOException{
		try(InputStream in=new FileInputStream(bucketMapPath)){
			Properties props=new Properties();
			props.load(in);
			for(Map.Entry entry:props.entrySet()){
				bucketMap.put(Integer.parseInt(entry.getKey().toString()), Integer.parseInt(entry.getValue().toString()));
			}
		}
	}
	/**
	 * 得到桶的权重，桶就是实际存储数据的DB实例
	 * 从0开始的桶编号为key，权重为值，权重默认为1。
	 * 键值必须都是整数
	 * @param bucket
	 * @return
	 */
	private int getWeight(int bucket){
		Integer w=weightMap.get(bucket);
		if(w==null){
			w=DEFAULT_WEIGHT;
		}
		return w;
	}
	/**
	 * 创建murmur_hash对象的种子，默认0
	 * @param seed
	 */
	public void setSeed(int seed){
		this.seed=seed;
	}
	/**
	 * 节点的数量
	 * @param count
	 */
	public void setCount(int count) {
		this.count = count;
	}
	/**
	 * 虚拟节点倍数，virtualBucketTimes*count就是虚拟结点数量
	 * @param virtualBucketTimes
	 */
	public void setVirtualBucketTimes(int virtualBucketTimes){
		this.virtualBucketTimes=virtualBucketTimes;
	}
	/**
	 * 节点的权重，没有指定权重的节点默认是1。以properties文件的格式填写，以从0开始到count-1的整数值也就是节点索引为key，以节点权重值为值。
	 * 所有权重值必须是正整数，否则以1代替
	 * @param weightMapPath
	 * @throws IOException 
	 * @throws  
	 */
	public void setWeightMapFile(String weightMapPath) throws IOException{
		Properties props=new Properties();
		try(BufferedReader reader=new BufferedReader(new InputStreamReader(this.getClass().getClassLoader().getResourceAsStream(weightMapPath), DEFAULT_CHARSET))){
			props.load(reader);
			for(Map.Entry entry:props.entrySet()){
				int weight=Integer.parseInt(entry.getValue().toString());
				weightMap.put(Integer.parseInt(entry.getKey().toString()), weight>0?weight:1);
			}
		}
	}
	/**
	 * 保存一致性hash的虚拟节点文件路径。
	 * 如果这个文件不存在或是空文件就按照指定的count, weightMapFile等构造新的MurmurHash数据结构并保存到这个路径的文件里。
	 * 如果这个文件已存在且不是空文件就加载这个文件里的内容作为MurmurHash数据结构，此时其它参数都忽略。
	 * 除第一次以外在之后增加节点时可以直接修改这个文件，不过不推荐这么做。如果节点数量变化了，推荐删除这个文件。
	 * 可以不指定这个路径，不指定路径时不会保存murmur hash
	 * @param bucketMapPath
	 */
	public void setBucketMapPath(String bucketMapPath){
		this.bucketMapPath=bucketMapPath;
	}
	@Override
	public Integer calculate(String columnValue) {
		SortedMap<Integer, Integer> tail = bucketMap.tailMap(hash.hashUnencodedChars(columnValue).asInt());
		if (tail.isEmpty()) {
		    return bucketMap.get(bucketMap.firstKey());
		}
		return tail.get(tail.firstKey());
	}

}