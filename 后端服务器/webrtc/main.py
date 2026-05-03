from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from typing import Dict, List, Set, Any
import json
import uuid
import asyncio
import logging
from datetime import datetime

# 配置日志
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="集成通讯服务器 - WebRTC信令 + 聊天功能")

# 添加CORS中间件，允许跨域请求
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


class IntegratedConnectionManager:
    """集成连接管理器 - 支持WebRTC信令和聊天功能"""
    
    def __init__(self):
        # WebSocket连接管理
        self.active_connections: Dict[str, WebSocket] = {}
        
        # 用户信息管理
        self.users: Dict[str, dict] = {}  # {user_id: {"nickname": str, "connected_at": datetime, "type": "chat"|"webrtc"}}
        
        # 聊天功能
        self.chat_rooms: Dict[str, List[str]] = {}  # {room_id: [user_id1, user_id2, ...]}
        
        # WebRTC信令功能
        self.webrtc_rooms: Dict[str, Set[str]] = {}  # {room_id: {client_id1, client_id2, ...}}
        self.client_rooms: Dict[str, str] = {}  # {client_id: room_id}

    async def connect(self, websocket: WebSocket, user_id: str, nickname: str = "匿名用户", connection_type: str = "chat"):
        """用户连接"""
        await websocket.accept()
        self.active_connections[user_id] = websocket
        self.users[user_id] = {
            "nickname": nickname,
            "connected_at": datetime.now(),
            "type": connection_type
        }
        
        if connection_type == "webrtc":
            logger.info(f"WebRTC客户端 {nickname}({user_id}) 已连接，当前总连接数: {len(self.active_connections)}")
            # 发送WebRTC连接确认
            await self.send_personal_message(user_id, {
                "type": "connected",
                "clientId": user_id
            })
        else:
            logger.info(f"聊天用户 {nickname}({user_id}) 已连接")
            # 发送聊天连接确认
            await self.send_personal_message(user_id, {
                "type": "system",
                "message": "连接成功",
                "user_id": user_id,
                "nickname": nickname,
                "timestamp": datetime.now().isoformat()
            })

    def disconnect(self, user_id: str):
        """用户断开连接"""
        if user_id in self.active_connections:
            del self.active_connections[user_id]
            
        if user_id in self.users:
            user_info = self.users[user_id]
            nickname = user_info["nickname"]
            connection_type = user_info.get("type", "chat")
            del self.users[user_id]
            
            if connection_type == "webrtc":
                logger.info(f"WebRTC客户端 {nickname}({user_id}) 已断开连接，剩余连接数: {len(self.active_connections)}")
                # 清理WebRTC房间
                if user_id in self.client_rooms:
                    room_id = self.client_rooms[user_id]
                    asyncio.create_task(self.leave_webrtc_room(user_id, room_id))
            else:
                logger.info(f"聊天用户 {nickname}({user_id}) 已断开连接")
                # 清理聊天室
                for room_id in list(self.chat_rooms.keys()):
                    if user_id in self.chat_rooms[room_id]:
                        self.chat_rooms[room_id].remove(user_id)
                        if len(self.chat_rooms[room_id]) == 0:
                            del self.chat_rooms[room_id]

    async def send_personal_message(self, user_id: str, message: dict):
        """发送个人消息"""
        if user_id in self.active_connections:
            try:
                await self.active_connections[user_id].send_text(json.dumps(message))
            except Exception as e:
                logger.error(f"发送消息给 {user_id} 时出错: {e}")

    async def send_to_chat_room(self, message: dict, room_id: str, sender_id: str = None):
        """发送消息到聊天室"""
        if room_id in self.chat_rooms:
            for user_id in self.chat_rooms[room_id]:
                if user_id != sender_id and user_id in self.active_connections:
                    await self.send_personal_message(user_id, message)

    async def broadcast_to_webrtc_room(self, room_id: str, message: dict, exclude_client: str = None):
        """向WebRTC房间内所有客户端广播消息"""
        if room_id not in self.webrtc_rooms:
            return

        for client_id in self.webrtc_rooms[room_id]:
            if exclude_client and client_id == exclude_client:
                continue
            if client_id in self.active_connections:
                await self.send_personal_message(client_id, message)

    def create_chat_room(self, user1_id: str, user2_id: str) -> str:
        """创建聊天室"""
        room_id = f"chat_{min(user1_id, user2_id)}_{max(user1_id, user2_id)}"
        self.chat_rooms[room_id] = [user1_id, user2_id]
        return room_id

    async def join_webrtc_room(self, client_id: str, room_id: str):
        """客户端加入WebRTC房间"""
        if room_id not in self.webrtc_rooms:
            self.webrtc_rooms[room_id] = set()
            logger.info(f"创建新WebRTC房间: {room_id}")

        # 如果客户端已在其他房间，先离开
        if client_id in self.client_rooms:
            old_room = self.client_rooms[client_id]
            logger.info(f"WebRTC客户端 {client_id} 从房间 {old_room} 切换到房间 {room_id}")
            await self.leave_webrtc_room(client_id, old_room)

        self.webrtc_rooms[room_id].add(client_id)
        self.client_rooms[client_id] = room_id

        room_size = len(self.webrtc_rooms[room_id])
        logger.info(f"WebRTC客户端 {client_id} 加入房间 {room_id}，房间内人数: {room_size}")

        # 通知房间内其他客户端有新用户加入
        await self.broadcast_to_webrtc_room(room_id, {
            "type": "user_joined",
            "clientId": client_id,
            "roomId": room_id
        }, exclude_client=client_id)

        # 向新加入的客户端发送房间内现有用户列表
        existing_clients = list(self.webrtc_rooms[room_id] - {client_id})
        await self.send_personal_message(client_id, {
            "type": "room_joined",
            "roomId": room_id,
            "existingClients": existing_clients
        })

    async def leave_webrtc_room(self, client_id: str, room_id: str):
        """客户端离开WebRTC房间"""
        if room_id in self.webrtc_rooms and client_id in self.webrtc_rooms[room_id]:
            self.webrtc_rooms[room_id].remove(client_id)
            remaining_users = len(self.webrtc_rooms[room_id])
            logger.info(f"WebRTC客户端 {client_id} 离开房间 {room_id}，房间剩余人数: {remaining_users}")

            # 通知房间内其他客户端有用户离开
            await self.broadcast_to_webrtc_room(room_id, {
                "type": "user_left",
                "clientId": client_id,
                "roomId": room_id
            })

            # 如果房间为空，删除房间
            if not self.webrtc_rooms[room_id]:
                del self.webrtc_rooms[room_id]
                logger.info(f"WebRTC房间 {room_id} 已空，已删除")

            if client_id in self.client_rooms:
                del self.client_rooms[client_id]
        else:
            logger.warning(f"WebRTC客户端 {client_id} 尝试离开不存在的房间 {room_id} 或不在该房间中")

    async def handle_webrtc_message(self, sender_id: str, message: dict):
        """处理WebRTC相关消息（offer, answer, ice-candidate）"""
        target_id = message.get("targetId")
        message_type = message.get("type")

        if not target_id:
            logger.error(f"WebRTC消息缺少targetId，发送者: {sender_id}，消息类型: {message_type}")
            return

        if target_id not in self.active_connections:
            logger.warning(f"目标客户端 {target_id} 不存在或已断开，无法转发 {message_type} 消息")
            return

        # 转发消息给目标客户端
        message["senderId"] = sender_id
        await self.send_personal_message(target_id, message)
        logger.info(f"成功转发 {message_type} 消息：{sender_id} -> {target_id}")

    def get_online_users(self) -> List[dict]:
        """获取在线用户列表"""
        return [
            {
                "user_id": user_id,
                "nickname": info["nickname"],
                "connected_at": info["connected_at"].isoformat(),
                "type": info.get("type", "chat")
            }
            for user_id, info in self.users.items()
        ]

    def get_server_stats(self) -> dict:
        """获取服务器统计信息"""
        chat_users = sum(1 for user in self.users.values() if user.get("type") == "chat")
        webrtc_users = sum(1 for user in self.users.values() if user.get("type") == "webrtc")
        
        return {
            "total_connections": len(self.active_connections),
            "chat_users": chat_users,
            "webrtc_users": webrtc_users,
            "chat_rooms": len(self.chat_rooms),
            "webrtc_rooms": len(self.webrtc_rooms),
            "server_uptime": datetime.now().isoformat()
        }


# 创建全局连接管理器
manager = IntegratedConnectionManager()


@app.get("/")
async def root():
    return {"message": "集成通讯服务器运行中 - 支持WebRTC信令和聊天功能"}


@app.get("/users/online")
async def get_online_users():
    """获取在线用户列表"""
    return {"online_users": manager.get_online_users()}


@app.get("/stats")
async def get_server_stats():
    """获取服务器统计信息"""
    return manager.get_server_stats()


@app.websocket("/ws/chat/{user_id}")
async def chat_websocket_endpoint(websocket: WebSocket, user_id: str, nickname: str = "匿名用户"):
    """聊天WebSocket连接端点"""
    await manager.connect(websocket, user_id, nickname, "chat")

    try:
        while True:
            # 接收客户端消息
            data = await websocket.receive_text()
            message_data = json.loads(data)
            message_type = message_data.get("type")

            if message_type == "chat":
                # 处理聊天消息
                target_user_id = message_data.get("target_user_id")
                content = message_data.get("content")

                if target_user_id and content:
                    # 创建或获取聊天室
                    room_id = manager.create_chat_room(user_id, target_user_id)

                    # 构造消息
                    chat_message = {
                        "type": "chat",
                        "from_user_id": user_id,
                        "from_nickname": manager.users[user_id]["nickname"],
                        "to_user_id": target_user_id,
                        "content": content,
                        "room_id": room_id,
                        "timestamp": datetime.now().isoformat()
                    }

                    # 发送给目标用户
                    await manager.send_personal_message(target_user_id, chat_message)

                    # 发送确认给发送者
                    await manager.send_personal_message(user_id, {
                        "type": "message_sent",
                        "message": "消息已发送",
                        "original_message": chat_message
                    })

            elif message_type == "get_users":
                # 获取在线用户列表
                await manager.send_personal_message(user_id, {
                    "type": "users_list",
                    "users": manager.get_online_users()
                })

            elif message_type == "ping":
                # 心跳检测
                await manager.send_personal_message(user_id, {
                    "type": "pong",
                    "timestamp": datetime.now().isoformat()
                })

    except WebSocketDisconnect:
        manager.disconnect(user_id)
    except Exception as e:
        logger.error(f"聊天WebSocket错误: {e}")
        manager.disconnect(user_id)


@app.websocket("/ws/webrtc/{client_id}")
async def webrtc_websocket_endpoint(websocket: WebSocket, client_id: str, nickname: str = "WebRTC客户端"):
    """WebRTC信令WebSocket连接端点"""
    await manager.connect(websocket, client_id, nickname, "webrtc")

    try:
        while True:
            # 接收客户端消息
            data = await websocket.receive_text()
            message_data = json.loads(data)
            message_type = message_data.get("type")
            
            logger.debug(f"收到来自WebRTC客户端 {client_id} 的消息类型: {message_type}")

            if message_type == "join_room":
                room_id = message_data.get("roomId")
                if room_id:
                    logger.info(f"WebRTC客户端 {client_id} 请求加入房间: {room_id}")
                    await manager.join_webrtc_room(client_id, room_id)
                else:
                    logger.error(f"WebRTC客户端 {client_id} 发送的join_room消息缺少roomId")

            elif message_type == "leave_room":
                room_id = message_data.get("roomId")
                if room_id:
                    logger.info(f"WebRTC客户端 {client_id} 请求离开房间: {room_id}")
                    await manager.leave_webrtc_room(client_id, room_id)
                else:
                    logger.error(f"WebRTC客户端 {client_id} 发送的leave_room消息缺少roomId")

            elif message_type in ["offer", "answer", "ice-candidate"]:
                logger.debug(f"处理来自WebRTC客户端 {client_id} 的WebRTC消息: {message_type}")
                await manager.handle_webrtc_message(client_id, message_data)

            else:
                logger.warning(f"来自WebRTC客户端 {client_id} 的未知消息类型: {message_type}")

    except WebSocketDisconnect:
        manager.disconnect(client_id)
    except Exception as e:
        logger.error(f"WebRTC WebSocket错误: {e}")
        manager.disconnect(client_id)


if __name__ == "__main__":
    import uvicorn

    logger.info("=" * 60)
    logger.info("集成通讯服务器启动中...")
    logger.info("功能包括:")
    logger.info("  - WebRTC视频通话信令服务")
    logger.info("  - 实时聊天功能")
    logger.info("  - 用户在线状态管理")
    logger.info("连接端点:")
    logger.info("  - 聊天: ws://localhost:8000/ws/chat/{user_id}?nickname={nickname}")
    logger.info("  - WebRTC: ws://localhost:8000/ws/webrtc/{client_id}?nickname={nickname}")
    logger.info("  - API文档: http://localhost:8000/docs")
    logger.info("=" * 60)
    
    uvicorn.run(app, host="0.0.0.0", port=8000)