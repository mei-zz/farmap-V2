interface UserList {
    id: number;
    name: string;
    role: "guest" | "expert" | "admin";
}

interface DeleteUserRequest {
    targetUser: UserList["name"];
}
interface DeleteUserResponse {
    message: string;
    status: number;
}
