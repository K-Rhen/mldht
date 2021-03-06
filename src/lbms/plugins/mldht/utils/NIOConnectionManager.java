/*
 *    This file is part of mlDHT.
 * 
 *    mlDHT is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 2 of the License, or
 *    (at your option) any later version.
 * 
 *    mlDHT is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 * 
 *    You should have received a copy of the GNU General Public License
 *    along with mlDHT.  If not, see <http://www.gnu.org/licenses/>.
 */
package lbms.plugins.mldht.utils;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import lbms.plugins.mldht.kad.DHT;
import lbms.plugins.mldht.kad.DHT.LogLevel;

public class NIOConnectionManager {
	
	ConcurrentLinkedQueue<Selectable> registrations = new ConcurrentLinkedQueue<>();
	ConcurrentLinkedQueue<Selectable> updateInterestOps = new ConcurrentLinkedQueue<>();
	List<Selectable> connections = new ArrayList<Selectable>();
	AtomicReference<Thread> workerThread = new AtomicReference<Thread>();
	
	String name;
	Selector selector;
	volatile boolean wakeupCalled;
	
	public NIOConnectionManager(String name) {
		this.name = name;
		try
		{
			selector = Selector.open();
		} catch (IOException e)
		{
			e.printStackTrace();
		}
	}
	
	int iterations;
	
	void selectLoop() {
		
		iterations = 0;
		lastNonZeroIteration = 0;
		
		while(true)
		{
			try
			{
				wakeupCalled = false;
				selector.select(100);
				wakeupCalled = false;
				
				processSelected();
				connectionChecks();
				handleRegistrations();
				updateInterestOps();

					
			} catch (Exception e)
			{
				DHT.log(e, LogLevel.Error);
			}
			
			iterations++;
			
			if(suspendOnIdle())
				break;
		}
	}
	
	void processSelected() throws IOException {
		Set<SelectionKey> keys = selector.selectedKeys();
		for(SelectionKey selKey : keys)
		{
			Selectable connection = (Selectable) selKey.attachment();
			connection.selectionEvent(selKey);
		}
		keys.clear();
	}
	
	
	long lastConnectionCheck;

	/*
	 * checks if connections need to be removed from the selector
	 */
	void connectionChecks() throws IOException {
		if((iterations & 0x0F) != 0)
			return;

		long now = System.currentTimeMillis();
		
		if(now - lastConnectionCheck < 500)
			return;
		lastConnectionCheck = now;
		
		for(Selectable conn : new ArrayList<Selectable>(connections)) {
			conn.doStateChecks(now);
			Optional.ofNullable(conn.getChannel()).map(c -> c.keyFor(selector)).filter(k -> !k.isValid()).ifPresent(k -> {
				connections.remove(conn);
			});
		}
	}
	
	void handleRegistrations() throws IOException {
		// register new connections
		Selectable toRegister = null;
		while((toRegister = registrations.poll()) != null)
		{
			connections.add(toRegister);
			toRegister.registrationEvent(NIOConnectionManager.this,toRegister.getChannel().register(selector, toRegister.calcInterestOps(),toRegister));
		}
	}
	
	HashSet<Selectable> toUpdate = new HashSet<>();
	
	void updateInterestOps() {
		while(true) {
			Selectable t = updateInterestOps.poll();
			if(t == null)
				break;
			toUpdate.add(t);
		}
		
		toUpdate.forEach(sel -> {
			SelectionKey k = sel.getChannel().keyFor(selector);
			if(k != null && k.isValid())
				k.interestOps(sel.calcInterestOps());
		});
		toUpdate.clear();
	}
	
	int lastNonZeroIteration;
	
	boolean suspendOnIdle() {
		if(connections.size() == 0 && registrations.peek() == null)
		{
			if(iterations - lastNonZeroIteration > 10)
			{
				workerThread.set(null);
				ensureRunning();
				return true;
			}
			return false;
		}
		
		lastNonZeroIteration = iterations;
			
		return false;
	}
	
	private void ensureRunning() {
		while(true)
		{
			Thread current = workerThread.get();
			if(current == null && registrations.peek() != null)
			{
				current = new Thread(this::selectLoop);
				current.setName(name);
				current.setDaemon(true);
				if(workerThread.compareAndSet(null, current))
				{
					current.start();
					break;
				}
			} else
			{
				break;
			}
		}
	}
	
	public void deRegister(Selectable connection)
	{
		connections.remove(connection);
	}
	
	public void register(Selectable connection)
	{
		registrations.add(connection);
		ensureRunning();
		selector.wakeup();
	}
	
	public void interestOpsChanged(Selectable sel)
	{
		updateInterestOps.add(sel);
		if(Thread.currentThread() != workerThread.get() && !wakeupCalled)
		{
			wakeupCalled = true;
			selector.wakeup();
		}
	}
	
	public Selector getSelector() {
		return selector;
	}

}
