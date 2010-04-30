

var room = 
{
	_serviceRoot: "/service/auction/",
	_topicRoot: "/auction/chat/",
	_roomId: null,
	_userId: null,
	
	join: function(roomid, username)
	{
		this._roomId = roomid;
		this._userId = username;
		
		dojox.cometd.startBatch();
		this._chatSub = dojox.cometd.subscribe(room._topicRoot + roomid, chatDisplay, "displayMessage");
		dojox.cometd.publish(room._topicRoot + roomid, 
		{
			user: username,
			join: true,
			chat: username + " has joined"
		});
		dojox.cometd.endBatch();
	},
	
	leave: function(roomid, username)
	{
		dojox.cometd.startBatch();
		dojox.cometd.publish(room._topicRoot + roomid, 
		{
			user: username,
			leave: true,
			chat: username + (username.indexOf('elvis-') == 0 ? ' has left the building' : ' has left')
		});
		dojox.cometd.unsubscribe(room._chatSub);
		dojox.cometd.endBatch();
		
		this._roomId = null;
		this._userId = null;
		this._chatSub = null;
	},
	
	sendMessage: function(roomid, username, text)
	{
		if (!text || !text.length) 
			return;
		
		var colons = text.indexOf("::");
		if (colons > 0) 
		{
			dojox.cometd.publish(room._serviceRoot + 'chat', 
			{
				room: room._topicRoot + roomid,
				user: username,
				chat: text.substring(colons + 2),
				peer: text.substring(0, colons)
			});
		}
		else 
		{
			dojox.cometd.publish(room._topicRoot + roomid, 
			{
				user: username,
				chat: text
			});
		}
	}
};

var bidHandler = 
{
	_bidder: null,
	_serviceRoot: "/service/auction/",
	_topicRoot: "/auction/item",
	_subscriptions: new Array(),
	
	registerBidder: function(name)
	{
		if (name == null || name.length == 0) 
		{
			return false;
		}
		else 
		{
			dojox.cometd.publish(bidHandler._serviceRoot + 'bidder', name);
			return true;
		}
	},
	
	handleRegistration: function(msg)
	{
		var bidder = msg.data;
		displayUtil.hide($('toptext'), true);
		bidHandler._bidder = bidder;
		bidDisplay.showRegisteredUser();
		
		dojox.cometd.publish(bidHandler._serviceRoot + 'categories', "" + bidder.username);
	},
	
	bid: function(amount, itemId)
	{
		var validAmount = displayUtil.validateCash(amount);
		bidHandler.watch(itemId);
		if (validAmount != null) 
		{
			amount = amount.replace(/\,/g, "");
			
			dojox.cometd.publish(room._serviceRoot + 'bid', 
			{
				itemId: itemId,
				amount: amount,
				username: bidHandler._bidder.username
			});
		}
		return validAmount != null;
	},
	
	watch: function(itemid)
	{
		if (!bidHandler._subscriptions[itemid]) 
		{
			bidHandler._subscriptions[itemid] = dojox.cometd.subscribe(bidHandler._topicRoot + itemid, bidDisplay, "displayBid");
		}
	},
	
	unwatch: function(itemid)
	{
		if (bidHandler._subscriptions[itemid]) 
		{
			dojox.cometd.unsubscribe(bidHandler._subscriptions[itemid]);
			bidHandler._subscriptions[itemid] = null;
		}
	}
};

var displayUtil = 
{
	show: function(element, displayStyle)
	{
	
		$(element).style.visibility = "visible";
		if (displayStyle != null) 
			$(element).style.display = displayStyle;
	},
	
	hide: function(element, displayNone)
	{
		$(element).style.visibility = "hidden";
		if (displayNone) 
			$(element).style.display = "none";
	},
	
	reveal: function(element, options)
	{
		new Effect.BlindDown(element, options);
	},
	
	unreveal: function(element, options)
	{
		new Effect.BlindUp(element, options);
	},
	
	formatCash: function(amount)
	{
		var str = displayUtil.validateCash("" + amount);
		if (str != null) 
		{
			if (!/^\$\d*/.test(str)) 
				str = "$" + str;
		}
		return str;
	},
	
	validateCash: function(amountStr)
	{
		var ok = true;
		if (amountStr == null || amountStr == "") 
			return "0";
		var str = amountStr.replace(/^\$/, "");
		
		var strNoCommas = str.replace(/\,/g, "");
		if (/^\d+(\.\d{1,2})?$/.test(strNoCommas)) 
		{
			return str;
		}
		return null;
	},
	
	displayError: function(errorString, exception)
	{
		alert(errorString);
	},
	
	replace: function(string, text, by)
	{
		var strLength = string.length, txtLength = text.length;
		if ((strLength == 0) || (txtLength == 0)) 
			return string;
		
		var i = string.indexOf(text);
		if ((!i) && (text != string.substring(0, txtLength))) 
			return string;
		if (i == -1) 
			return string;
		
		var newstr = string.substring(0, i) + by;
		
		if (i + txtLength < strLength) 
			newstr += displayUtil.replace(string.substring(i + txtLength, strLength), text, by);
		
		return newstr;
	}
	
};

// functions for DWRUtil.addRows
var bidDisplay = 
{

	_bidItemDisplay: [function(bid)
	{
		thename = document.createElement("p");
		thename.id = "Itm" + bid.itemId;
		thename.innerHTML = bid.itemName;
		return thename;
	}, function(bid)
	{
		return (bid.amount == null ? "" : displayUtil.formatCash(bid.amount));
	}, function(bid)
	{
		return bid.bidder;
	}, function(bid)
	{
		var thisbid = bid;
		
		//create a bid button to reveal an input box
		var bidButton = document.createElement("button");
		bidButton.id = "Btn" + thisbid.itemId;
		bidButton.itemId = thisbid.itemId;
		bidButton.itemName = thisbid.itemName;
		bidButton.innerHTML = "bid";
		return bidButton;
	}, function(bid)
	{
		var chatButton = document.createElement("button");
		chatButton.id = "Chat" + bid.itemId;
		chatButton.itemId = bid.itemId;
		chatButton.itemName = bid.itemName;
		chatButton.innerHTML = "chat";
		chatButton.setAttribute("class", "chat");
		chatButton.onclick = function(event)
		{
			//close any previous chat
			chatDisplay.leaveRoom();
			//enter the new room
			chatDisplay.enterRoom(chatButton.id, bid.itemId, bidHandler._bidder.username);
		}
		return chatButton;
	}
]	,
	
	showRegistration: function(message)
	{
		if (message != null) 
		{
			alert(message);
		}
	},
	
	showRegisteredUser: function()
	{
		//show a welcome message
		var logindiv = document.getElementById("login");
		while (logindiv.hasChildNodes()) 
		{
			logindiv.removeChild(logindiv.firstChild);
		}
		welcome = document.createElement("span");
		welcome.appendChild(document.createTextNode("welcome "));
		username = document.createElement("b");
		username.appendChild(document.createTextNode(bidHandler._bidder.name + "(" + bidHandler._bidder.username + ")"));
		welcome.appendChild(username);
		logindiv.appendChild(welcome);
		//show all parts of the application now except for the chat
		displayUtil.show("catalogstuff", "block");
		displayUtil.show("auction", "block");
	},
	
	
	undisplayBid: function(itemId)
	{
		var bidTr = document.getElementById(itemId);
		var contents = $("contents");
		contents.removeChild(bidTr);
	},
	
	displayBid: function(message)
	{
		if (message && message.data) 
		{
			var itemId = message.data.itemId;
			var bidder = message.data.bidder;
			var amount = message.data.amount;
			if (itemId) 
				bidDisplay.displayBidDetails(itemId, null, amount, bidder);
		}
	},
	
	displayBidDetails: function(itemId, itemName, amount, bidder)
	{
		var bidList = $("contents");
		var bidTr = document.getElementById(itemId);
		if (bidTr == null) 
		{
			var bid = new Object();
			bid.itemId = itemId;
			bid.itemName = itemName;
			bid.amount = amount;
			bid.bidder = bidder;
			var bids = new Array();
			bids[0] = bid;
			
			DWRUtil.addRows("contents", bids, bidDisplay._bidItemDisplay, 
			{
				rowCreator: function(options)
				{
					var row = document.createElement("tr");
					var thedata = options.rowData;
					row.id = thedata.itemId;
					return row;
				},
				
				cellCreator: function(options)
				{
					var cell = document.createElement("td");
					var thedata = options.rowData;
					if (options.cellNum == 1) 
					{
						cell.id = "Amnt" + thedata.itemId;
						cell.className = "amountcell";
					}
					else if (options.cellNum == 2) 
					{
						cell.id = "Bidder" + thedata.itemId;
						cell.className = "biddercell";
					}
					else if (options.cellNum == 3) 
					{
						cell.className = "bidcell";
					}
					else if (options.cellNum == 4) 
					{
						cell.className = "chatcell";
					}
					return cell;
				}
			});
			
			//add an inplace editor on the Bid button
			new NonAjax.InPlaceEditor("Btn" + itemId, null, 
			{
				callback: function(form, val)
				{
					if (!bidHandler.bid(val, bid.itemId, bid.itemName)) 
						alert('Please enter a decimal amount without a leading $');
				},
				onStartEdit: function(textElement)
				{
					dollars = DWRUtil.getValue("Amnt" + bid.itemId);
					//textElement.value=displayUtil.validateCash(dollars);
					dollars = dollars.replace(/^\$/, "").replace(/\,/g, "");
					textElement.value = dollars;
				}
			});
		}
		else 
		{
			//update existing current bid and bidder in the row
			dollarValue = "";
			if (amount != null && amount != "") 
				dollarValue = displayUtil.formatCash(amount);
			DWRUtil.setValue("Amnt" + itemId, dollarValue);
			
			if (bidder) 
			{
				var highBidder = bidder.username;
				DWRUtil.setValue("Bidder" + itemId, highBidder);
				if (highBidder == bidHandler._bidder.username) 
				{
					new Effect.Highlight("Amnt" + itemId, 
					{
						startcolor: '#ffffff',
						endcolor: '#ccffcc',
						restorecolor: '#ccffcc'
					});
				}
				else 
				{
					new Effect.Highlight("Amnt" + itemId, 
					{
						startcolor: '#ffffff',
						endcolor: '#ffcccc',
						restorecolor: '#ffcccc'
					});
				}
			}
		}
	}
};



var catalogDisplay = 
{
	_categorySelected: null,
	
	// Functions for DWRUtil.addRows
	_itemsDisplay: [function(item)
	{
		var div = document.createElement("div");
		div.appendChild(document.createElement("br"));
		div.align = "center";
		var img = div.appendChild(document.createElement("img"));
		img.src = "images/" + item.itemId + ".jpg";
		div.appendChild(document.createElement("br"));
		
		return div;
	}, function(item)
	{
		var div = document.createElement("div");
		div.align = "left";
		
		div.appendChild(document.createElement("br"));
		
		var bname = div.appendChild(document.createElement("b"));
		var fname = bname.appendChild(document.createElement("font"));
		fname.color = "#FF0000";
		fname.innerHTML = item.name;
		
		//div.appendChild(document.createElement("br"));
		div.appendChild(document.createElement("br"));
		
		var desc = div.appendChild(document.createElement("span"));
		desc.innerHTML = item.description;
		
		div.appendChild(document.createElement("br"));
		div.appendChild(document.createElement("br"));
		
		//put on a "watch/bid" button if we aren't already watching	    
		var btn = div.appendChild(document.createElement("button"));
		btn.id = "watch" + item.itemId;
		btn.innerHTML = "watch";
		if (document.getElementById(item.itemId) != null) 
		{
			btn.style.visibility = "hidden"; //hide ourselves if item shown in auction
		}
		btn.onclick = function(event)
		{
			//add to my auction, getting the highest bid
			bidDisplay.displayBidDetails(item.itemId, item.name, null, null);
			
			//make sure we watch for all messages on the item's topic
			bidHandler.watch(item.itemId);
			btn.style.visibility = "hidden"; //hide ourselves once pressed
			//document.getElementById('phrase').focus();
		}
		
		div.appendChild(document.createElement("br"));
		
		return div;
	}
]	,
	
	displayItems: function(msg)
	{
		var auctionitems = msg.data;
		DWRUtil.removeAllRows("auctionitems");
		if (auctionitems.length == 0) 
		{
			DWRUtil.setValue("itemhdr", "Items");
		}
		else 
		{
			DWRUtil.addRows("auctionitems", auctionitems, catalogDisplay._itemsDisplay);
		}
	},
	
	
	// Functions for DWRUtil.addRows of categories
	_categoryItemDisplay: [function(categoryitem)
	{
		var link = document.createElement("span");
		link.itemId = categoryitem.categoryId;
		link.innerHTML = categoryitem.categoryName;
		
		return link;
	}
]	,
	
	displayCategories: function(msg)
	{
		var categoryList = msg.data;
		DWRUtil.removeAllRows("categories");
		DWRUtil.addRows("categories", categoryList, catalogDisplay._categoryItemDisplay, 
		{
			rowCreator: function(options)
			{
				return document.createElement("tr");
			},
			cellCreator: function(options)
			{
				var cell = document.createElement("td");
				var categoryData = options.rowData;
				cell.id = "cat" + categoryData.categoryId;
				cell.setAttribute("class", "noselect");
				cell.onclick = function(event)
				{
					if (catalogDisplay._categorySelected != null) 
					{
						$(catalogDisplay._categorySelected).setAttribute("class", "noselect");
						$(catalogDisplay._categorySelected).className = "noselect";
					}
					catalogDisplay._categorySelected = cell.id;
					cell.setAttribute("class", "catselect");
					cell.className = "catselect";
					catalogDisplay.displayItemsByCategory(categoryData.categoryId, categoryData.categoryName);
				}
				cell.onmouseover = function(event)
				{
					if (cell.className != "catselect") 
					{
						cell.className = "hovered";
					}
				}
				cell.onmouseout = function(event)
				{
					if (cell.className == "hovered") 
					{
						cell.className = "noselect";
					}
				}
				return cell;
			}
		});
		displayUtil.show('searchbox', "inline");
		displayUtil.show('searchbtn', "inline");
	},
	
	searchFormSubmitHandler: function()
	{
		var searchExp = $("searchbox").value;
		DWRUtil.setValue("searchbox", "");
		
		dojox.cometd.publish(room._serviceRoot + 'search', searchExp);
	},
	
	displayItemsByCategory: function(categoryId, categoryName)
	{
		DWRUtil.removeAllRows("auctionitems");
		DWRUtil.setValue("itemhdr", categoryName);
		dojox.cometd.publish(room._serviceRoot + 'category', categoryId);
	}
	
};

var chatDisplay = 
{
	displayMembers: function(memberList)
	{
		membersHTML = "";
		if (memberList != null) 
		{
			for (i = 0; i < memberList.length; i++) 
			{
				membersHTML += "<p>" + memberList[i] + "</p>";
			}
		}
		$("members").innerHTML = membersHTML;
	},
	
	displayImage: function(itemId)
	{
		var imageSrc = "images/" + itemId + ".jpg";
		var imageHTML = "<center><img src='" + imageSrc + "'/></center>";
		
		$("chatitem").innerHTML = imageHTML;
	},
	
	displayMessage: function(message)
	{
		chatArea = $('chat');
		if (message) 
		{
		
			if (message.data instanceof Array) 
			{
				chatDisplay.displayMembers(message.data);
				return;
			}
			
			var roomId = room._roomId;
			var userId = message.data.user;
			var msg = message.data.chat;
			
			if (message.data.join || message.data.leave) 
			{
				//show the new joiner
				chatArea.innerHTML += "<p><i>" + msg + "</i></p>";
				chatDisplay.displayImage(roomId);
			}
			else 
			{
				chatArea.innerHTML += "<p>" + userId + ": " + msg + "</p>"
			}
			chatArea.scrollTop = chatArea.scrollHeight - chatArea.clientHeight;
		}
	},
	
	enterRoom: function(buttonId, roomId, userId)
	{
		displayUtil.show("chatcontainer", "block");
		displayUtil.hide(buttonId, false);
		$('chatclose').chatId = buttonId;
		$('chatclose').roomId = roomId;
		$('chattitle').innerHTML = "Chat: " + $(buttonId).itemName;
		room.join(roomId, userId);
		document.getElementById('phrase').focus();
	},
	
	leaveRoom: function()
	{
		if (room._roomId != null) 
		{
			room.leave(room._roomId, bidHandler._bidder.username);
			displayUtil.hide('chatcontainer');
			displayUtil.show($('chatclose').chatId, "inline");
			$('chatclose').roomId = "";
			$('chatclose').chatId = "";
			$('chat').innerHTML = "";
			$('members').innerHTML = "";
			$('chattitle').innerHTML = "Chat";
		}
	}
};

var EvUtil = 
{
	getKeyCode: function(ev)
	{
		var keyc;
		if (window.event) 
			keyc = window.event.keyCode;
		else 
			keyc = ev.keyCode;
		return keyc;
	}
};


var auctionBehaviour = 
{

	'#username': function(element)
	{
		element.setAttribute("autocomplete", "OFF");
		element.onkeyup = function(ev)
		{
			var keyc = EvUtil.getKeyCode(ev);
			if (keyc == 13 || keyc == 10) 
			{
				var name = $('username').value;
				if (!bidHandler.registerBidder(name)) 
					bidDisplay.showRegistration("Please enter a user name");
				Behaviour.apply();
			}
			return true;
		}
	},
	
	'#phrase': function(element)
	{
		element.setAttribute("autocomplete", "OFF");
		element.onkeyup = function(ev)
		{
			var keyc = EvUtil.getKeyCode(ev);
			if (keyc == 13 || keyc == 10) 
			{
				room.sendMessage($('chatclose').roomId, bidHandler._bidder.username, $('phrase').value);
				$('phrase').value = "";
				$('phrase').focus();
			}
		}
	},
	
	'#joinbtn': function(element)
	{
		element.onclick = function(event)
		{
			var name = $('username').value;
			if (!bidHandler.registerBidder(name)) 
				bidDisplay.showRegistration("Please enter a user name");
			Behaviour.apply();
			return true;
		}
	},
	
	'#chatclose': function(element)
	{
		element.onclick = function(event)
		{
			chatDisplay.leaveRoom();
		}
	},
	
	'#searchbtn': function(element)
	{
		element.onclick = function(event)
		{
			catalogDisplay.searchFormSubmitHandler();
		}
	},
	
	'#sendChat': function(element)
	{
		element.onclick = function(event)
		{
			room.sendMessage($('chatclose').roomId, bidHandler._bidder.username, $('phrase').value);
			$('phrase').value = "";
			$('phrase').focus();
		}
	}
	
	
};

Behaviour.register(auctionBehaviour);
Behaviour.addLoadEvent(function()
{
	$('username').focus();
	dojox.cometd.subscribe(bidHandler._serviceRoot + 'categories', catalogDisplay, "displayCategories");
	dojox.cometd.subscribe(bidHandler._serviceRoot + 'category', catalogDisplay, "displayItems");
	dojox.cometd.subscribe(bidHandler._serviceRoot + 'search', catalogDisplay, "displayItems");
	dojox.cometd.subscribe(bidHandler._serviceRoot + 'bidder', bidHandler, "handleRegistration");
});


